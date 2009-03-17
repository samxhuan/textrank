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
 * Java implementation of the TextRank algorithm by Mihalcea, et al.
 *    http://lit.csci.unt.edu/index.php/Graph-based_NLP
 *
 * @author Paco NATHAN
 */

public class
    TextRank
{
    // logging

    private final static Log log_ =
        LogFactory.getLog(TextRank.class.getName());


    /**
     * Public definitions.
     */

    public final static String NLP_RESOURCES = "nlp.resources";


    /**
     * Reads the given input file as text, returning a String.
     *
     * @param fileName - The name of input file.
     * @return the full text as a String
     * @throws IOException in case of I/O error.
     */

    public static String
	readFile (final String file_name)
	throws IOException
    {
        final Reader r =
	    new BufferedReader(new InputStreamReader(new FileInputStream(file_name), "UTF-8"));
        try {
	    final StringBuilder sb = new StringBuilder();
	    final char[] buff = new char[256];
	    int len;

	    while ((len = r.read(buff)) > -1) {
		sb.append(buff, 0, len);
	    }

	    return sb.toString();
        } finally {
            r.close();
        }
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
	final String log_config = args[0];
	final String data_file = args[1];
	final String lang_code = args[2];
	final String graph_file = args[3];

	long start_time = 0L;
	long elapsed_time = 0L;

	boolean use_wordnet = true; // false
	use_wordnet = use_wordnet && ("en".equals(lang_code));

        // set up logging for debugging and instrumentation
        
        PropertyConfigurator.configure(log_config);

	// load the text from a file

	final String text = readFile(data_file);

	// set up the language model

	final String resource_path =
	    new File(System.getProperty(NLP_RESOURCES)).getPath();

	final Language lang =
	    Language.buildLanguage(lang_code, resource_path);

	if (use_wordnet) {
	    WordNet.buildDictionary(resource_path);
	}


	//////////////////////////////////////////////////
	// PASS 1: construct a graph from PoS tags

	final Cache cache = new Cache();
	final Graph graph = new Graph();

	start_time = System.currentTimeMillis();

	// scan sentences to construct a graph of relevent morphemes

	for (String sent_text : lang.splitParagraph(text)) {
	    final Sentence s = new Sentence(sent_text.trim());
	    s.mapTokens(lang, cache, graph);

	    if (log_.isDebugEnabled()) {
		log_.debug("s: " + s.text);
		log_.debug(s.md5_hash);
	    }
	}


	//////////////////////////////////////////////////
	// PASS 2: run TextRank to determine keywords

	final int max_results =
	    (int) Math.round((double) graph.size() * Graph.KEYWORD_REDUCTION_FACTOR);

	graph.runTextRank();
	graph.sortResults(max_results);

	final Graph ngram_subgraph =
	    NGram.collectNGrams(lang, cache, graph.getRankThreshold());

	elapsed_time = System.currentTimeMillis() - start_time;

	if (log_.isInfoEnabled()) {
	    log_.info("TEXT_BYTES: " + text.length());
	    log_.info("ELAPSED_TIME: " + elapsed_time);
	    log_.info("GRAPH_SIZE: " + graph.size());
	}


	//////////////////////////////////////////////////
	// PASS 3: lemmatize selected keywords and phrases

	start_time = System.currentTimeMillis();

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
		NGram gram = (NGram) n.value;

		if (gram.nodes.size() > 1) {
		    SynsetLink.addKeyWord(synset_subgraph, n, gram.getCollocation(), POS.NOUN);
		}
	    }

	    synset_subgraph =
		SynsetLink.pruneGraph(synset_subgraph, graph);
	}

	// augment the graph with n-grams added as nodes

	for (Node n : ngram_subgraph.values()) {
	    graph.put(n.key, n);

	    for (Node keyword_node : ((NGram) n.value).nodes) {
		n.connect(keyword_node);
	    }
	}

	elapsed_time = System.currentTimeMillis() - start_time;

	if (log_.isInfoEnabled()) {
	    log_.info("ELAPSED_TIME: " + elapsed_time);
	}


	//////////////////////////////////////////////////
	// PASS 4: re-run TextRank on the augmented graph

	start_time = System.currentTimeMillis();

	graph.runTextRank();
	//graph.sortResults(graph.size() / 2);

	// collect stats for metrics

	final int ngram_max_count =
	    NGram.calcStats(ngram_subgraph);

	if (use_wordnet) {
	    SynsetLink.calcStats(synset_subgraph);
	}

	elapsed_time = System.currentTimeMillis() - start_time;

	if (log_.isInfoEnabled()) {
	    log_.info("ELAPSED_TIME: " + elapsed_time);

	    if (log_.isDebugEnabled()) {
		log_.debug("RANK: " + ngram_subgraph.dist_stats);

		for (Node n : new TreeSet<Node>(ngram_subgraph.values())) {
		    final NGram gram = (NGram) n.value;
		    log_.debug(gram.getCount() + " " + n.rank + " " + gram.text /* + " @ " + gram.renderContexts() */);
		}
	    }

	    if (log_.isDebugEnabled()) {
		log_.debug("RANK: " + synset_subgraph.dist_stats);

		for (Node n : new TreeSet<Node>(synset_subgraph.values())) {
		    final SynsetLink s = (SynsetLink) n.value;
		    log_.info("emit: " + s.synset + " " + n.rank + " " + s.relation);
		}
	    }
	}


	//////////////////////////////////////////////////
	// PASS 5: construct a metric space for overall ranking

	final HashMap<NGram, MetricVector> metric_space = new HashMap<NGram, MetricVector>();

	final double link_min = ngram_subgraph.dist_stats.getMin();
	final double link_coeff = ngram_subgraph.dist_stats.getMax() - ngram_subgraph.dist_stats.getMin();

	final double count_min = 1;
	final double count_coeff = (double) ngram_max_count - 1;

	final double synset_min = synset_subgraph.dist_stats.getMin();
	final double synset_coeff = synset_subgraph.dist_stats.getMax() - synset_subgraph.dist_stats.getMin();

	for (Node n : ngram_subgraph.values()) {
	    final NGram gram = (NGram) n.value;

	    final double link_rank = (n.rank - link_min) / link_coeff;
	    final double count_rank = (gram.getCount() - count_min) / count_coeff;
	    final double synset_rank = use_wordnet ? n.maxNeighbor(synset_min, synset_coeff) : 0.0D;

	    final MetricVector mv = new MetricVector(gram, link_rank, count_rank, synset_rank);
	    metric_space.put(gram, mv);
	}

	// show the best results

	for (MetricVector mv : new TreeSet<MetricVector>(metric_space.values())) {
	    if (mv.metric > 0.05D) {
		if (log_.isInfoEnabled()) {
		    log_.info(mv.render() + " " + mv.value.text);
		}
	    }
	}


	//////////////////////////////////////////////////
	// PASS 6: serialize the graph

	for (Node n : graph.values()) {
	    n.marked = false;
	}

	final TreeSet<String> entries = new TreeSet<String>();

	for (Node n : ngram_subgraph.values()) {
	    final NGram gram = (NGram) n.value;
	    final MetricVector mv = metric_space.get(gram);
	    final StringBuilder sb = new StringBuilder();

	    sb.append("rank").append('\t');
	    sb.append(n.getId()).append('\t');
	    sb.append(mv.render());
	    entries.add(sb.toString());

	    n.serializeGraph(entries);
	}

        final OutputStreamWriter fw =
	    new OutputStreamWriter(new FileOutputStream(graph_file), "UTF-8");
						   
        try {
	    for (String entry : entries) {
		fw.write(entry, 0, entry.length());
		fw.write('\n');
	    }
        } finally {
            fw.close();
        }
    }
}
