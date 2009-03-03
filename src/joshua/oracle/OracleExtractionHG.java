/* This file is part of the Joshua Machine Translation System.
 * 
 * Joshua is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free
 * Software Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */
package joshua.oracle;

import joshua.decoder.ff.lm.LMFFDPState;
import joshua.decoder.hypergraph.DiskHyperGraph;
import joshua.decoder.hypergraph.HGNode;
import joshua.decoder.hypergraph.HyperEdge;
import joshua.decoder.hypergraph.HyperGraph;
import joshua.decoder.hypergraph.KBestExtractor;
import joshua.decoder.BuildinSymbol;
import joshua.decoder.Support;
import joshua.corpus.SymbolTable;
import joshua.util.FileUtility;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/**
 * approximated BLEU
 * (1) do not consider clipping effect
 * (2) in the dynamic programming, do not maintain different states for different hyp length
 * (3) brief penalty is calculated based on the avg ref length
 * (4) using sentence-level BLEU, instead of doc-level BLEU
 * 
 * @author Zhifei Li, <zhifei.work@gmail.com> (Johns Hopkins University)
 * @version $LastChangedDate$
 */
public class OracleExtractionHG extends SplitHg {
	static String BACKOFF_LEFT_LM_STATE_SYM="<lzfbo>";
	public int BACKOFF_LEFT_LM_STATE_SYM_ID;//used for equivelant state
	
	static String NULL_LEFT_LM_STATE_SYM="<lzflnull>";
	public int NULL_LEFT_LM_STATE_SYM_ID;//used for equivelant state
	
	static String NULL_RIGHT_LM_STATE_SYM="<lzfrnull>";
	public int NULL_RIGHT_LM_STATE_SYM_ID;//used for equivelant state
	
	
//	int[] ref_sentence;//reference string (not tree)
	protected  int src_sent_len =0;
	protected  int ref_sent_len =0;
	protected  int g_lm_order=4; //only used for decide whether to get the LM state by this class or not in compute_state
	static protected boolean do_local_ngram_clip =false;
	static protected boolean maitain_length_state = false;
	static protected  int g_bleu_order=4;
	
	static boolean using_left_equiv_state = false;
	static boolean using_right_equiv_state = false;
	HashMap tbl_suffix = new HashMap();
	HashMap tbl_prefix = new HashMap();
	static PrefixGrammar grammar_prefix = new PrefixGrammar();//TODO
	static PrefixGrammar grammar_suffix = new PrefixGrammar();//TODO
	
//	key: item; value: best_deduction, best_bleu, best_len, # of n-gram match where n is in [1,4]
	protected HashMap tbl_ref_ngrams = new HashMap();
	

	static boolean always_maintain_seperate_lm_state = true; //if true: the virtual item maintain its own lm state regardless whether lm_order>=g_bleu_order
	
	SymbolTable p_symbolTable;
	
	int lm_feat_id=0; //the baseline LM feature id
	
	public OracleExtractionHG(SymbolTable symbolTable, int lm_feat_id_){
		this.p_symbolTable = symbolTable;
		this.lm_feat_id = lm_feat_id_;
		this.BACKOFF_LEFT_LM_STATE_SYM_ID = p_symbolTable.addTerminal(BACKOFF_LEFT_LM_STATE_SYM);
		this.NULL_LEFT_LM_STATE_SYM_ID = p_symbolTable.addTerminal(NULL_RIGHT_LM_STATE_SYM);
		this.NULL_RIGHT_LM_STATE_SYM_ID = p_symbolTable.addTerminal(NULL_RIGHT_LM_STATE_SYM);
	}
	
	/*for 919 sent, time_on_reading: 148797
	time_on_orc_extract: 580286*/
	public static void main(String[] args) throws IOException {
	
		/*String f_hypergraphs="C:\\Users\\zli\\Documents\\mt03.src.txt.ss.nbest.hg.items";
		String f_rule_tbl="C:\\Users\\zli\\Documents\\mt03.src.txt.ss.nbest.hg.rules";
		String f_ref_files="C:\\Users\\zli\\Documents\\mt03.ref.txt.1";
		String f_orc_out ="C:\\Users\\zli\\Documents\\mt03.orc.txt";*/
		if(args.length!=6){
			System.out.println("wrong command, correct command should be: java Decoder f_hypergraphs f_rule_tbl f_ref_files f_orc_out lm_order orc_extract_nbest");
			System.out.println("num of args is "+ args.length);
			for(int i=0; i <args.length; i++)System.out.println("arg is: " + args[i]);
			System.exit(0);		
		}		
		String f_hypergraphs = args[0].trim();
		String f_rule_tbl = args[1].trim();
		String f_ref_files = args[2].trim();
		String f_orc_out =  args[3].trim();
		int lm_order = new Integer(args[4].trim());	
		boolean orc_extract_nbest=new Boolean(args[5].trim());	; //oracle extraction from nbest or hg
		
//		????????????????????????????????????????????????????
		int baseline_lm_feat_id = 0; 
		//??????????????????????????????????????
		
		SymbolTable p_symbolTable = new BuildinSymbol(null);
		
		KBestExtractor kbest_extractor =null;
		int topN=300;//TODO
		boolean extract_unique_nbest = true;//TODO
		boolean do_ngram_clip_nbest = true; //TODO
		if(orc_extract_nbest==true){
			System.out.println("oracle extraction from nbest list");
			kbest_extractor = new KBestExtractor(p_symbolTable);
		}
		
		BufferedWriter orc_out = FileUtility.getWriteFileStream(f_orc_out);
		DiskHyperGraph dhg_write = null; 
		int lm_feat_id = 0; //TODO
		if(orc_extract_nbest==false){
			dhg_write = new DiskHyperGraph(p_symbolTable, lm_feat_id);
			dhg_write.init_write(f_orc_out+".hg.items", false, -1);
		}
		
		long start_time = System.currentTimeMillis();
		long time_on_reading = 0;
		long time_on_orc_extract = 0;
		BufferedReader t_reader_ref = FileUtility.getReadFileStream(f_ref_files);
		DiskHyperGraph dhg_read  = new DiskHyperGraph(p_symbolTable, lm_feat_id);
	
		dhg_read.init_read(f_hypergraphs, f_rule_tbl, null);
		
		OracleExtractionHG orc_extractor = new OracleExtractionHG(p_symbolTable, baseline_lm_feat_id);
		String ref_sent= null;
		int sent_id=0;
		while( (ref_sent=FileUtility.read_line_lzf(t_reader_ref))!= null ){
			System.out.println("############Process sentence " + sent_id);
			//start_time = System.currentTimeMillis();
			sent_id++;
			//if(sent_id>10)break;
			
			HyperGraph hg = dhg_read.read_hyper_graph();
			if(hg==null)continue;
			String orc_sent=null;
			double orc_bleu=0;
			
			//System.out.println("read disk hyp: " + (System.currentTimeMillis()-start_time));
			//time_on_reading += System.currentTimeMillis()-start_time;
			//start_time = System.currentTimeMillis();
			
			if(orc_extract_nbest){
				Object[] res = orc_extractor.oracle_extract_nbest(kbest_extractor, hg, topN, extract_unique_nbest, do_ngram_clip_nbest, ref_sent);
				orc_sent = (String) res[0];
				orc_bleu = (Double) res[1];
			}else{				
				HyperGraph hg_oracle = orc_extractor.oracle_extract_hg(hg, hg.sent_len, lm_order, ref_sent);
				orc_sent =  HyperGraph.extractViterbiString(p_symbolTable, hg_oracle.goal_item);
				orc_bleu = orc_extractor.get_best_goal_cost(hg, orc_extractor.g_tbl_split_virtual_items);
				if(dhg_write!=null) dhg_write.save_hyper_graph(hg_oracle);
				///time_on_orc_extract += System.currentTimeMillis()-start_time;
				System.out.println("num_virtual_items: " + orc_extractor.g_num_virtual_items + " num_virtual_dts: " + orc_extractor.g_num_virtual_deductions);
				//System.out.println("oracle extract: " + (System.currentTimeMillis()-start_time));
			}
			
			orc_out.write(orc_sent+"\n");
			System.out.println("orc bleu is " + orc_bleu);
		}
		t_reader_ref.close();
		orc_out.close();
		if(dhg_write!=null) dhg_write.write_rules_non_parallel(f_orc_out + ".hg.rules");
		
		System.out.println("time_on_reading: " + time_on_reading);
		System.out.println("time_on_orc_extract: " + time_on_orc_extract);
		System.out.println("total running time: "
			+ (System.currentTimeMillis() - start_time));
	}
	
	
	
	//find the oracle hypothesis in the nbest list
	public Object[] oracle_extract_nbest(KBestExtractor kbest_extractor, HyperGraph hg, int n,   boolean extract_unique_nbest, boolean do_ngram_clip, String ref_sent){
		if(hg.goal_item==null) return null;
		kbest_extractor.reset_state();				
		int next_n=0;
		double orc_bleu=-1;
		String orc_sent=null;
		while(true){
			String hyp_sent = kbest_extractor.get_kth_hyp(hg.goal_item, ++next_n, -1, null, extract_unique_nbest, false, false);
			if(hyp_sent==null || next_n > n) break;
			double t_bleu = compute_sentence_bleu(this.p_symbolTable, ref_sent, hyp_sent, do_ngram_clip, 4);
			if(t_bleu>orc_bleu){
				orc_bleu = t_bleu;
				orc_sent = hyp_sent;
			}			
		}
		System.out.println("Oracle sent: " + orc_sent);
		System.out.println("Oracle bleu: " + orc_bleu);
		Object[] res = new Object[2];
		res[0]=orc_sent;
		res[1]=orc_bleu;
		return res;
	}

	
	public HyperGraph oracle_extract_hg(HyperGraph hg, int src_sent_len_in, int lm_order,  String ref_sent_str){
		int[] ref_sent = this.p_symbolTable.addTerminals(ref_sent_str.split("\\s+"));
		g_lm_order=lm_order;		
		src_sent_len = src_sent_len_in;
		ref_sent_len = ref_sent.length;		
		
		tbl_ref_ngrams.clear();
		get_ngrams(tbl_ref_ngrams,g_bleu_order,ref_sent, false);	
		if(using_left_equiv_state || using_right_equiv_state){
			tbl_prefix.clear();	tbl_suffix.clear();
			setup_prefix_suffix_tbl(ref_sent,  g_bleu_order, tbl_prefix, tbl_suffix);
			setup_prefix_suffix_grammar(ref_sent,  g_bleu_order, grammar_prefix, grammar_suffix);//TODO
		}
		split_hg(hg);
		
		//System.out.println("best bleu is " +  get_best_goal_cost( hg, g_tbl_split_virtual_items));
		return get_1best_tree_hg(hg, g_tbl_split_virtual_items);
	}
	
	
	
	/*This procedure does
	 * (1) identify all possible match
	 * (2) add a new deduction for each matches*/
	protected  void process_one_combination_axiom(HGNode parent_item, HashMap virtual_item_sigs, HyperEdge cur_dt){
		if(cur_dt.get_rule()==null){System.out.println("error null rule in axiom"); System.exit(0);}
		double avg_ref_len = (parent_item.j-parent_item.i>=src_sent_len) ? ref_sent_len :  (parent_item.j-parent_item.i)*ref_sent_len*1.0/src_sent_len;//avg len?
		double bleu_score[] = new double[1];
		DPStateOracle dps = compute_state(parent_item, cur_dt, null, tbl_ref_ngrams, do_local_ngram_clip, g_lm_order, avg_ref_len, bleu_score, tbl_suffix, tbl_prefix);
		VirtualDeduction t_dt = new VirtualDeduction(cur_dt, null, -bleu_score[0]);//cost: -best_bleu
		g_num_virtual_deductions++;
		add_deduction(parent_item, virtual_item_sigs,  t_dt, dps, true);			
	}
	
	/*This procedure does
	 * (1) create a new deduction (based on cur_dt and ant_virtual_item)
	 * (2) find whether an Item can contain this deduction (based on virtual_item_sigs which is a hashmap specific to a parent_item)
	 * 	(2.1) if yes, add the deduction, 
	 *  (2.2) otherwise
	 *  	(2.2.1) create a new item
	 *		(2.2.2) and add the item into virtual_item_sigs
	 **/
	protected  void process_one_combination_nonaxiom(HGNode parent_item, HashMap virtual_item_sigs, HyperEdge cur_dt, ArrayList<VirtualItem> l_ant_virtual_item){
		if(l_ant_virtual_item==null){System.out.println("wrong call in process_one_combination_nonaxiom"); System.exit(0);}	
		double avg_ref_len = (parent_item.j-parent_item.i>=src_sent_len) ? ref_sent_len :  (parent_item.j-parent_item.i)*ref_sent_len*1.0/src_sent_len;//avg len?
		double bleu_score[] = new double[1];
		DPStateOracle dps = compute_state(parent_item, cur_dt, l_ant_virtual_item, tbl_ref_ngrams,  do_local_ngram_clip, g_lm_order, avg_ref_len, bleu_score, tbl_suffix, tbl_prefix);
		VirtualDeduction t_dt = new VirtualDeduction(cur_dt, l_ant_virtual_item, -bleu_score[0]);//cost: -best_bleu	
		g_num_virtual_deductions++;
		add_deduction(parent_item, virtual_item_sigs,  t_dt, dps, true);    	
	}


	//DPState maintain all the state information at an item that is required during dynamic programming
	protected static class DPStateOracle extends DPState {
		int best_len; //this may not be used in the signature
		int[] ngram_matches;
		int[] left_lm_state;
		int[] right_lm_state;	
		
		public DPStateOracle(int blen, int[] matches, int[] left, int[] right){
			best_len = blen;
			ngram_matches = matches;
			left_lm_state = left;
			right_lm_state = right;
		}
		
		protected String get_signature(){		
			StringBuffer res = new StringBuffer();
			if(maitain_length_state==true){	res.append(best_len); res.append(" ");}
			if(left_lm_state!=null)//goal-item have null state
				for(int i=0; i< left_lm_state.length; i++){
					res.append(left_lm_state[i]);
					res.append(" ");
				}
			res.append("lzf ");	
			
			if(right_lm_state!=null)//goal-item have null state
				for(int i=0; i< right_lm_state.length; i++){
					res.append(right_lm_state[i]);
					res.append(" ");
				}
			//if(left_lm_state==null || right_lm_state==null)System.out.println("sig is: " + res.toString());
			return res.toString();
		}
		
		protected void print(){
			StringBuffer res = new StringBuffer();
			res.append("DPstate: best_len: ");
			res.append(best_len);
			for(int i=0; i<ngram_matches.length; i++){
				res.append("; ngram: ");
				res.append(ngram_matches[i]);
			}
			System.out.println(res.toString());
		}
	}
	
	
//	########################## commmon funcions #####################
	//based on tbl_oracle_states, tbl_ref_ngrams, and dt, get the state
	//get the new state: STATE_BEST_DEDUCT STATE_BEST_BLEU STATE_BEST_LEN NGRAM_MATCH_COUNTS
	protected  DPStateOracle compute_state(HGNode parent_item, HyperEdge dt, ArrayList<VirtualItem> l_ant_virtual_item,  HashMap tbl_ref_ngrams, 
			boolean do_local_ngram_clip, int lm_order, double ref_len, double[] bleu_score, HashMap tbl_suffix, HashMap tbl_prefix){	
		//##### deductions under "goal item" does not have rule
		if(dt.get_rule()==null){
			if(l_ant_virtual_item.size()!=1){System.out.println("error deduction under goal item have more than one item"); System.exit(0);}
			bleu_score[0] = -l_ant_virtual_item.get(0).best_virtual_deduction.best_cost;
			return  new DPStateOracle(0, null, null,null);//no DPState at all
		}
		
		//################## deductions *not* under "goal item"		
		HashMap new_ngram_counts = new HashMap();//new ngrams created due to the combination
		HashMap old_ngram_counts = new HashMap();//the ngram that has already been computed
		int total_hyp_len =0;
		int[] num_ngram_match = new int[g_bleu_order];
		int[] en_words = dt.get_rule().english;
		
		//####calulate new and old ngram counts, and len
    	ArrayList words= new ArrayList();
    	ArrayList left_state_sequence = null; //used for compute left- and right- lm state
    	ArrayList right_state_sequence = null; //used for compute left- and right- lm state
    	int correct_lm_order = lm_order;
    	if(always_maintain_seperate_lm_state==true || lm_order<g_bleu_order) {
    		left_state_sequence = new ArrayList();
    		right_state_sequence = new ArrayList();
    		correct_lm_order = g_bleu_order;//if lm_order is smaller than g_bleu_order, we will get the lm state by ourself
    	}
    	
    	//#### get left_state_sequence, right_state_sequence, total_hyp_len, num_ngram_match
    	for(int c=0; c<en_words.length; c++){
    		int c_id = en_words[c];
    		if(this.p_symbolTable.isNonterminal(c_id)==true){
    			int index=this.p_symbolTable.getTargetNonterminalIndex(c_id);
    			DPStateOracle ant_state = (DPStateOracle) l_ant_virtual_item.get(index).dp_state;    			
    			total_hyp_len += ant_state.best_len;
    			for(int t=0; t<g_bleu_order; t++)
    				num_ngram_match[t] += ant_state.ngram_matches[t];
    	  			
    			int[] l_context = ant_state.left_lm_state;
    			int[] r_context = ant_state.right_lm_state;
    			for(int t : l_context){//always have l_context
    				words.add(t);
    				if(left_state_sequence!=null && left_state_sequence.size()<g_bleu_order-1) left_state_sequence.add(t);
    			}
    			get_ngrams(old_ngram_counts, g_bleu_order, l_context, true);    			
    			if(r_context.length>=correct_lm_order-1){//the right and left are NOT overlapping	    	
    				get_ngrams(new_ngram_counts, g_bleu_order, words, true);
    				get_ngrams(old_ngram_counts, g_bleu_order, r_context, true);
	    			words.clear();//start a new chunk    
	    			if(right_state_sequence!=null)right_state_sequence.clear();
	    			for(int t : r_context)
	    				words.add(t);	    			
	    		}
    			if(right_state_sequence!=null)
    				for(int t : r_context)
    					right_state_sequence.add(t);
    		}else{
    			words.add(c_id);
    			total_hyp_len += 1;
    			if(left_state_sequence!=null && left_state_sequence.size()<g_bleu_order-1)left_state_sequence.add(c_id);
    			if(right_state_sequence!=null) right_state_sequence.add(c_id);
    		}
    	}
    	get_ngrams(new_ngram_counts, g_bleu_order, words, true);
    
    	//####now deduct ngram counts
    	Iterator iter = new_ngram_counts.keySet().iterator();
    	while(iter.hasNext()){
    		String ngram = (String)iter.next();
    		if(tbl_ref_ngrams.containsKey(ngram)){
	    		int final_count = (Integer)new_ngram_counts.get(ngram);
	    		if(old_ngram_counts.containsKey(ngram)){
	    			final_count -= (Integer)old_ngram_counts.get(ngram);
	    			if(final_count<0){System.out.println("error: negative count for ngram: "+ this.p_symbolTable.getWord(11844) + "; new: " + new_ngram_counts.get(ngram) +"; old: " +old_ngram_counts.get(ngram) ); System.exit(0);}
	    		}
	    		if(final_count>0){//TODO: not correct/global ngram clip
	    			if(do_local_ngram_clip)
	    				num_ngram_match[ngram.split("\\s+").length-1] += Support.find_min(final_count,(Integer)tbl_ref_ngrams.get(ngram)) ;
	    			else 
	    				num_ngram_match[ngram.split("\\s+").length-1] += final_count; //do not do any cliping    			
	    		}
    		}
    	}
    	
    	//####now calculate the BLEU score and state
    	int[] left_lm_state = null;
		int[] right_lm_state= null;
		if(always_maintain_seperate_lm_state==false && lm_order>=g_bleu_order){	//do not need to change lm state, just use orignal lm state
			LMFFDPState state     = (LMFFDPState) parent_item.getFeatDPState(this.lm_feat_id);
			left_lm_state = state.getLeftLMStateWords();
			right_lm_state = state.getRightLMStateWords();
		}else{
			left_lm_state = get_left_equiv_state(left_state_sequence, tbl_suffix);
			right_lm_state = get_right_equiv_state(right_state_sequence, tbl_prefix); 
			
			//debug
			//System.out.println("lm_order is " + lm_order);
			//compare_two_int_arrays(left_lm_state, (int[])parent_item.tbl_states.get(Symbol.LM_L_STATE_SYM_ID));
			//compare_two_int_arrays(right_lm_state, (int[])parent_item.tbl_states.get(Symbol.LM_R_STATE_SYM_ID));
			//end						
		}
		bleu_score[0] = compute_bleu(total_hyp_len, ref_len, num_ngram_match, g_bleu_order);
		return  new DPStateOracle(total_hyp_len, num_ngram_match, left_lm_state, right_lm_state);
	}
	
	
	private int[] get_left_equiv_state(ArrayList left_state_sequence, HashMap tbl_suffix){
		int l_size = (left_state_sequence.size()<g_bleu_order-1)? left_state_sequence.size() : (g_bleu_order-1);
		int[] left_lm_state = new int[l_size];
		if(using_left_equiv_state==false || l_size<g_bleu_order-1){//regular
			for(int i=0; i<l_size; i++)
				left_lm_state[i] = (Integer)left_state_sequence.get(i);
		}else{			
			for(int i=l_size-1; i>=0; i--){//right to left
				if(is_a_suffix_in_tbl(left_state_sequence, 0, i, tbl_suffix)){
				//if(is_a_suffix_in_grammar(left_state_sequence, 0, i, grammar_suffix)){
					for(int j=i; j>=0; j--)
						left_lm_state[j] = (Integer)left_state_sequence.get(j);
					break;
				}else{
					left_lm_state[i] = this.NULL_LEFT_LM_STATE_SYM_ID;
				}
			}
			//System.out.println("origi left:" + Symbol.get_string(left_state_sequence) + "; equiv left:" + Symbol.get_string(left_lm_state));
		}
		return left_lm_state;
	}
	
	private boolean is_a_suffix_in_tbl(ArrayList left_state_sequence, int start_pos, int end_pos, HashMap tbl_suffix){
		if((Integer)left_state_sequence.get(end_pos)==this.NULL_LEFT_LM_STATE_SYM_ID)
			return false;
		StringBuffer suffix = new StringBuffer();
		for(int i=end_pos; i>=start_pos; i--){//right-most first
			suffix.append(left_state_sequence.get(i));
			if(i>start_pos) suffix.append(" ");
		}		
		return (Boolean) tbl_suffix.containsKey(suffix.toString());
	}
	
	private boolean is_a_suffix_in_grammar(ArrayList left_state_sequence, int start_pos, int end_pos, PrefixGrammar grammar_suffix){
		if((Integer)left_state_sequence.get(end_pos)==this.NULL_LEFT_LM_STATE_SYM_ID)
			return false;
		ArrayList suffix = new ArrayList();
		for(int i=end_pos; i>=start_pos; i--){//right-most first
			suffix.add(left_state_sequence.get(i));
		}		
		return grammar_suffix.contain_ngram(suffix,  0,  suffix.size()-1);
	}
	
	
	private  int[] get_right_equiv_state(ArrayList right_state_sequence, HashMap tbl_prefix){
		int r_size = (right_state_sequence.size()<g_bleu_order-1)? right_state_sequence.size() : (g_bleu_order-1);
		int[] right_lm_state = new int[r_size];
		if(using_right_equiv_state==false || r_size<g_bleu_order-1){//regular
			for(int i=0; i<r_size; i++)
				right_lm_state[i] = (Integer)right_state_sequence.get(right_state_sequence.size()-r_size+i);
		}else{			
			for(int i=0; i<r_size; i++){//left to right
				if(is_a_prefix_in_tbl(right_state_sequence, right_state_sequence.size()-r_size+i, right_state_sequence.size()-1, tbl_prefix)){
				//if(is_a_prefix_in_grammar(right_state_sequence, right_state_sequence.size()-r_size+i, right_state_sequence.size()-1, grammar_prefix)){
					for(int j=i; j<r_size; j++)
						right_lm_state[j] = (Integer)right_state_sequence.get(right_state_sequence.size()-r_size+j);
					break;
				}else{
					right_lm_state[i] = this.NULL_RIGHT_LM_STATE_SYM_ID;
				}
			}
			//System.out.println("origi right:" + Symbol.get_string(right_state_sequence)+ "; equiv right:" + Symbol.get_string(right_lm_state));
			
		}
		return right_lm_state;
	}
	
	private boolean is_a_prefix_in_tbl(ArrayList right_state_sequence, int start_pos, int end_pos, HashMap tbl_prefix){
		if((Integer)right_state_sequence.get(start_pos)==this.NULL_RIGHT_LM_STATE_SYM_ID)
			return false;
		StringBuffer prefix = new StringBuffer();
		for(int i=start_pos; i<=end_pos; i++){
			prefix.append(right_state_sequence.get(i));
			if(i<end_pos) prefix.append(" ");
		}		
		return (Boolean) tbl_prefix.containsKey(prefix.toString());
	}
	
	private boolean is_a_prefix_in_grammar(ArrayList right_state_sequence, int start_pos, int end_pos, PrefixGrammar gr_prefix){
		if((Integer)right_state_sequence.get(start_pos)==this.NULL_RIGHT_LM_STATE_SYM_ID)
			return false;
		return gr_prefix.contain_ngram(right_state_sequence,  start_pos,  end_pos);
	}

	public static void compare_two_int_arrays(int[] a, int[] b){
		if(a.length!=b.length){System.out.println("error: two arrays do not have same size"); System.exit(0);}
		for(int i=0; i<a.length; i++)
			if(a[i]!=b[i]){System.out.println("error: elements in two arrays are not same"); System.exit(0);}
	}
	
	//sentence-bleu: BLEU= bp * prec; where prec = exp (sum 1/4 * log(prec[order]))
	public static double compute_bleu(int hyp_len, double ref_len, int[] num_ngram_match, int bleu_order){
		if(hyp_len<=0 || ref_len<=0){System.out.println("error: ref or hyp is zero len"); System.exit(0);}
		double res=0;		
		double wt = 1.0/bleu_order;
		double prec = 0;
		double smooth_factor=1.0;
		for(int t=0; t<bleu_order && t<hyp_len; t++){
			if(num_ngram_match[t]>0)
				prec += wt*Math.log(num_ngram_match[t]*1.0/(hyp_len-t));
			else{
				smooth_factor *= 0.5;//TODO
				prec += wt*Math.log(smooth_factor/(hyp_len-t));
			}
		}
		double bp = (hyp_len>=ref_len) ? 1.0 : Math.exp(1-ref_len/hyp_len);	
		res = bp*Math.exp(prec);
		//System.out.println("hyp_len: " + hyp_len + "; ref_len:" + ref_len + "prec: " + Math.exp(prec) + "; bp: " + bp + "; bleu: " + res);
		return res;
	}
	
	//accumulate ngram counts into tbl
	public void get_ngrams(HashMap tbl, int order, int[] wrds, boolean ignore_null_equiv_symbol){
		for(int i=0; i<wrds.length; i++)
			for(int j=0; j<order && j+i<wrds.length; j++){//ngram: [i,i+j]
				boolean contain_null=false;
				StringBuffer ngram = new StringBuffer();
				for(int k=i; k<=i+j; k++){
					if(wrds[k]==this.NULL_LEFT_LM_STATE_SYM_ID || wrds[k]==this.NULL_RIGHT_LM_STATE_SYM_ID ){
						contain_null=true;
						if(ignore_null_equiv_symbol) break;
					}
					ngram.append(wrds[k]);
					if(k<i+j) ngram.append(" ");
				}
				if(ignore_null_equiv_symbol && contain_null) continue;//skip this ngram
				String ngram_str = ngram.toString();
				if(tbl.containsKey(ngram_str))
					tbl.put(ngram_str, (Integer)tbl.get(ngram_str)+1);
				else
					tbl.put(ngram_str, 1);
			}
	}
	
//	accumulate ngram counts into tbl
	public void get_ngrams(HashMap tbl, int order, ArrayList wrds, boolean ignore_null_equiv_symbol){
		for(int i=0; i<wrds.size(); i++)
			for(int j=0; j<order && j+i<wrds.size(); j++){//ngram: [i,i+j]
				boolean contain_null=false;
				StringBuffer ngram = new StringBuffer();
				for(int k=i; k<=i+j; k++){
					int t_wrd = (Integer) wrds.get(k);
					if(t_wrd==this.NULL_LEFT_LM_STATE_SYM_ID || t_wrd==this.NULL_RIGHT_LM_STATE_SYM_ID ){
						contain_null=true;
						if(ignore_null_equiv_symbol) break;
					}
					ngram.append(t_wrd);
					if(k<i+j) ngram.append(" ");
				}
				if(ignore_null_equiv_symbol && contain_null) continue;//skip this ngram
				String ngram_str = ngram.toString();
				if(tbl.containsKey(ngram_str))
					tbl.put(ngram_str, (Integer)tbl.get(ngram_str)+1);
				else
					tbl.put(ngram_str, 1);
			}
	}
	
	
	//do_ngram_clip: consider global n-gram clip
	public  double compute_sentence_bleu(SymbolTable p_symbol, String ref_sent, String hyp_sent, boolean do_ngram_clip, int bleu_order){
		int[] numeric_ref_sent = p_symbol.addTerminals(ref_sent.split("\\s+"));
		int[] numeric_hyp_sent = p_symbol.addTerminals(hyp_sent.split("\\s+"));
		return compute_sentence_bleu(numeric_ref_sent, numeric_hyp_sent, do_ngram_clip, bleu_order);		
	}
	
	public  double compute_sentence_bleu( int[] ref_sent, int[] hyp_sent, boolean do_ngram_clip, int bleu_order){
		double res_bleu = 0;
		int order =4;
		HashMap ref_ngram_tbl = new HashMap();
		get_ngrams(ref_ngram_tbl, order, ref_sent,false);
		HashMap hyp_ngram_tbl = new HashMap();
		get_ngrams(hyp_ngram_tbl, order, hyp_sent,false);
		
		int[] num_ngram_match = new int[order];
		for(Iterator it = hyp_ngram_tbl.keySet().iterator(); it.hasNext();){
			String ngram = (String) it.next();
			if(ref_ngram_tbl.containsKey(ngram)){
				if(do_ngram_clip)
					num_ngram_match[ngram.split("\\s+").length-1] += Support.find_min((Integer)ref_ngram_tbl.get(ngram),(Integer)hyp_ngram_tbl.get(ngram)); //ngram clip
				else
					num_ngram_match[ngram.split("\\s+").length-1] += (Integer)hyp_ngram_tbl.get(ngram);//without ngram count clipping    			
    		}
		}
		res_bleu = compute_bleu(hyp_sent.length, ref_sent.length, num_ngram_match, bleu_order);
		//System.out.println("hyp_len: " + hyp_sent.length + "; ref_len:" + ref_sent.length + "; bleu: " + res_bleu +" num_ngram_matches: " + num_ngram_match[0] + " " +num_ngram_match[1]+
		//		" " + num_ngram_match[2] + " " +num_ngram_match[3]);

		return res_bleu;
	}
			
	private static void print_state(Object[] state){
		System.out.println("State is");
		for(int i=0; i< state.length; i++)
			System.out.print(state[i] + " ---- ");
		System.out.println();
	}
	
	
	//#### equivalent lm stuff ############
	public static void setup_prefix_suffix_tbl(int[] wrds,  int order, HashMap prefix_tbl, HashMap suffix_tbl){
		for(int i=0; i<wrds.length; i++)
			for(int j=0; j<order && j+i<wrds.length; j++){//ngram: [i,i+j]
				StringBuffer ngram = new StringBuffer();	
				//### prefix
				for(int k=i; k<i+j; k++){//all ngrams [i,i+j-1]
					ngram.append(wrds[k]);
					prefix_tbl.put(ngram.toString(),true);
					ngram.append(" ");
				}				
				//### suffix: right-most wrd first
				ngram = new StringBuffer();
				for(int k=i+j; k>i; k--){//all ngrams [i+1,i+j]: reverse order
					ngram.append(wrds[k]);
					suffix_tbl.put(ngram.toString(),true);//stored in reverse order
					ngram.append(" ");
				}				
			}
	}
	
	
//	#### equivalent lm stuff ############
	public static void setup_prefix_suffix_grammar(int[] wrds,  int order, PrefixGrammar prefix_gr, PrefixGrammar suffix_gr){
		for(int i=0; i<wrds.length; i++)
			for(int j=0; j<order && j+i<wrds.length; j++){//ngram: [i,i+j]
				//### prefix
				prefix_gr.add_ngram(wrds, i, i+j-1);//ngram: [i,i+j-1]
				 			
				//### suffix: right-most wrd first
				int[] reverse_wrds = new int[j];
				for(int k=i+j, t=0; k>i; k--){//all ngrams [i+1,i+j]: reverse order
					reverse_wrds[t++] = wrds[k];
				}
				suffix_gr.add_ngram(reverse_wrds, 0, j-1);
			}
	}
	
	
	/*a backoff node is a hashtable, it may include:
	 * (1) probabilititis for next words
	 * (2) pointers to a next-layer backoff node (hashtable)
	 * (3) backoff weight for this node
	 * (4) suffix/prefix flag to indicate that there is ngrams start from this suffix
     */
	private static class PrefixGrammar {
		HashMap root = new HashMap();
		
		//add prefix information
		public void add_ngram(int[] wrds, int start_pos, int end_pos){			
			//######### identify the position, and insert the trinodes if necessary
			HashMap pos = root;
			for(int k=start_pos; k <=end_pos; k++){
				int cur_sym_id=wrds[k];
				HashMap next_layer = (HashMap)pos.get(cur_sym_id);
				if(next_layer!=null){
					pos=next_layer;
				}else{		
					HashMap tem = new HashMap();//next layer node
					pos.put(cur_sym_id, tem); 
					pos = tem;
				}
			}
		}
		
		public boolean contain_ngram(ArrayList wrds, int start_pos, int end_pos){
			if(end_pos<start_pos)return false;
			HashMap pos = root;
			for(int k=start_pos; k <=end_pos; k++){
				int cur_sym_id= (Integer)wrds.get(k);
				HashMap next_layer = (HashMap)pos.get(cur_sym_id);
				if(next_layer!=null){
					pos=next_layer;
				}else{
					return false;
				}
			}
			return true;
		}			
	} 	
}