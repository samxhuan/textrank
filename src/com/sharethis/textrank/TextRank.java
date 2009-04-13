/*
Copyright (c) 2009, ShareThis, Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.

    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.

    * Neither the name of the ShareThis, Inc., nor the names of its
      contributors may be used to endorse or promote products derived
      from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.sharethis.textrank;

import com.sharethis.common.IOUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;

import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.TreeSet;

import net.didion.jwnl.data.POS;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.log4j.PropertyConfigurator;


/**
 * Java implementation of the TextRank algorithm by Rada Mihalcea, et al.
 *    http://lit.csci.unt.edu/index.php/Graph-based_NLP
 *
 * @author paco@sharethis.com
 */

public class
    TextRank
{
    // logging

    private final static Log LOG =
        LogFactory.getLog(TextRank.class.getName());


    /**
     * Public definitions.
     */

    public final static String NLP_RESOURCES = "nlp.resources";
    public final static double MIN_NORMALIZED_RANK = 0.05D;
    public final static int MAX_NGRAM_LENGTH = 5;
    public final static long MAX_WORDNET_TEXT = 2000L;


    /**
     * Protected members.
     */

    protected final Cache cache = new Cache();
    protected final Graph graph = new Graph();
    protected final HashMap<NGram, MetricVector> metric_space = new HashMap<NGram, MetricVector>();

    protected Graph ngram_subgraph = null;

    protected long start_time = 0L;
    protected long elapsed_time = 0L;


    /**
     * Re-initialize the timer.
     */

    public void
	initTime ()
    {
	start_time = System.currentTimeMillis();
    }


    /**
     * Report the elapsed time with a label.
     */

    public void
	markTime (final String label)
    {
	elapsed_time = System.currentTimeMillis() - start_time;

	if (LOG.isInfoEnabled()) {
	    LOG.info("ELAPSED_TIME:\t" + elapsed_time + "\t" + label);
	}
    }


    /**
     * Serialize the graph to a file which can be rendered.
     */

    public void
	serializeGraph (final String graph_file)
	throws Exception
    {
	for (Node n : graph.values()) {
	    n.marked = false;
	}

	final TreeSet<String> entries = new TreeSet<String>();

	for (Node n : ngram_subgraph.values()) {
	    final NGram gram = (NGram) n.value;
	    final MetricVector mv = metric_space.get(gram);

	    if (mv != null) {
		final StringBuilder sb = new StringBuilder();

		sb.append("rank").append('\t');
		sb.append(n.getId()).append('\t');
		sb.append(mv.render());
		entries.add(sb.toString());

		n.serializeGraph(entries);
	    }
	}

        final OutputStreamWriter fw =
	    new OutputStreamWriter(new FileOutputStream(graph_file), "UTF-8");
						   
        try {
	    for (String entry : entries) {
		fw.write(entry, 0, entry.length());
		fw.write('\n');
	    }
        }
	finally {
            fw.close();
        }
    }


    /**
     * Serialize resulting graph to a string.
     */

    public String
	toString ()
    {
	final TreeSet<MetricVector> key_phrase_list = new TreeSet<MetricVector>(metric_space.values());
	final StringBuilder sb = new StringBuilder();

	for (MetricVector mv : key_phrase_list) {
	    if (mv.metric >= MIN_NORMALIZED_RANK) {
		sb.append(mv.render()).append("\t").append(mv.value.text).append("\n");
	    }
	}

	return sb.toString();
    }


    /**
     * Accessor for the graph.
     */

    public Graph
	getGraph ()
    {
	return graph;
    }


    /**
     * Accessor for the resulting key phrase list.
     */

    public HashMap<NGram, MetricVector>
	getMetricSpace ()
    {
	return metric_space;
    }


    /**
     * Run the TextRank algorithm on the given semi-structured text
     * (e.g., HtmlParser results from crawled web content) to build a
     * graph of weighted key phrases.
     */

    public void
	buildGraph (final String text, final String res_path, final Language lang, final boolean use_wordnet)
	throws Exception
    {
	if (use_wordnet) {
	    WordNet.buildDictionary(res_path);
	}

	//////////////////////////////////////////////////
	// PASS 1: construct a graph from PoS tags

	initTime();

	// scan sentences to construct a graph of relevent morphemes

	for (String sent_text : lang.splitParagraph(text)) {
	    final Sentence s = new Sentence(sent_text.trim());
	    s.mapTokens(lang, cache, graph);

	    if (LOG.isDebugEnabled()) {
		LOG.debug("s: " + s.text);
		LOG.debug(s.md5_hash);
	    }
	}

	markTime("construct_graph");

	//////////////////////////////////////////////////
	// PASS 2: run TextRank to determine keywords

	initTime();

	final int max_results =
	    (int) Math.round((double) graph.size() * Graph.KEYWORD_REDUCTION_FACTOR);

	graph.runTextRank();
	graph.sortResults(max_results);

	ngram_subgraph = NGram.collectNGrams(lang, cache, graph.getRankThreshold());

	markTime("basic_textrank");

	if (LOG.isInfoEnabled()) {
	    LOG.info("TEXT_BYTES:\t" + text.length());
	    LOG.info("GRAPH_SIZE:\t" + graph.size());
	}

	//////////////////////////////////////////////////
	// PASS 3: lemmatize selected keywords and phrases

	initTime();

	Graph synset_subgraph = new Graph();

	if (use_wordnet) {
	    // test the lexical value of nouns and adjectives in WordNet

	    for (Node n: graph.values()) {
		final KeyWord kw = (KeyWord) n.value;

		if (lang.isNoun(kw.pos)) {
		    SynsetLink.addKeyWord(synset_subgraph, n, kw.text, POS.NOUN);
		}
		else if (lang.isAdjective(kw.pos)) {
		    SynsetLink.addKeyWord(synset_subgraph, n, kw.text, POS.ADJECTIVE);
		}
	    }

	    // test the collocations in WordNet

	    for (Node n : ngram_subgraph.values()) {
		final NGram gram = (NGram) n.value;

		if (gram.nodes.size() > 1) {
		    SynsetLink.addKeyWord(synset_subgraph, n, gram.getCollocation(), POS.NOUN);
		}
	    }

	    synset_subgraph =
		SynsetLink.pruneGraph(synset_subgraph, graph);
	}

	// augment the graph with n-grams added as nodes

	for (Node n : ngram_subgraph.values()) {
	    final NGram gram = (NGram) n.value;

	    if (gram.length < MAX_NGRAM_LENGTH) {
		graph.put(n.key, n);

		for (Node keyword_node : gram.nodes) {
		    n.connect(keyword_node);
		}
	    }
	}

	markTime("augment_graph");

	//////////////////////////////////////////////////
	// PASS 4: re-run TextRank on the augmented graph

	initTime();

	graph.runTextRank();
	//graph.sortResults(graph.size() / 2);

	// collect stats for metrics

	final int ngram_max_count =
	    NGram.calcStats(ngram_subgraph);

	if (use_wordnet) {
	    SynsetLink.calcStats(synset_subgraph);
	}

	markTime("ngram_textrank");

	if (LOG.isInfoEnabled()) {
	    if (LOG.isDebugEnabled()) {
		LOG.debug("RANK: " + ngram_subgraph.dist_stats);

		for (Node n : new TreeSet<Node>(ngram_subgraph.values())) {
		    final NGram gram = (NGram) n.value;
		    LOG.debug(gram.getCount() + " " + n.rank + " " + gram.text /* + " @ " + gram.renderContexts() */);
		}
	    }

	    if (LOG.isDebugEnabled()) {
		LOG.debug("RANK: " + synset_subgraph.dist_stats);

		for (Node n : new TreeSet<Node>(synset_subgraph.values())) {
		    final SynsetLink s = (SynsetLink) n.value;
		    LOG.info("emit: " + s.synset + " " + n.rank + " " + s.relation);
		}
	    }
	}

	//////////////////////////////////////////////////
	// PASS 5: construct a metric space for overall ranking

	initTime();

	final double link_min = ngram_subgraph.dist_stats.getMin();
	final double link_coeff = ngram_subgraph.dist_stats.getMax() - ngram_subgraph.dist_stats.getMin();

	final double count_min = 1;
	final double count_coeff = (double) ngram_max_count - 1;

	final double synset_min = synset_subgraph.dist_stats.getMin();
	final double synset_coeff = synset_subgraph.dist_stats.getMax() - synset_subgraph.dist_stats.getMin();

	for (Node n : ngram_subgraph.values()) {
	    final NGram gram = (NGram) n.value;

	    if (gram.length < MAX_NGRAM_LENGTH) {
		final double link_rank = (n.rank - link_min) / link_coeff;
		final double count_rank = (gram.getCount() - count_min) / count_coeff;
		final double synset_rank = use_wordnet ? n.maxNeighbor(synset_min, synset_coeff) : 0.0D;

		final MetricVector mv = new MetricVector(gram, link_rank, count_rank, synset_rank);
		metric_space.put(gram, mv);
	    }
	}

	markTime("normalize_ranks");
    }


    //////////////////////////////////////////////////////////////////////
    // command line interface
    //////////////////////////////////////////////////////////////////////

    /**
     * Main entry point.
     */

    public static void
	main (final String[] args)
	throws Exception
    {
	final String log4j_conf = args[0];
	final String data_file = args[1];
	final String lang_code = args[2];
	final String graph_file = args[3];

        // set up logging for debugging and instrumentation
        
        PropertyConfigurator.configure(log4j_conf);

	// load the text from a file

	final String text = IOUtils.readFile(data_file);

	// filter out overly large files

	boolean use_wordnet = true; // false
	use_wordnet = use_wordnet && ("en".equals(lang_code));

	if (text.length() > MAX_WORDNET_TEXT) {
	    use_wordnet = false;
	}

	// set up the language model

	final String res_path =
	    new File(System.getProperty(NLP_RESOURCES)).getPath();

	final Language lang =
	    Language.buildLanguage(lang_code, res_path);

	// main entry point for the algorithm

	final TextRank tr = new TextRank();

	tr.buildGraph(text, res_path, lang, use_wordnet);
	LOG.info(tr);
    }
}
