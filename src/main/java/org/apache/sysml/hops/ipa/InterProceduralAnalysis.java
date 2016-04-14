/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.hops.ipa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.sysml.conf.ConfigurationManager;
import org.apache.sysml.hops.BinaryOp;
import org.apache.sysml.hops.DataGenOp;
import org.apache.sysml.hops.DataOp;
import org.apache.sysml.hops.FunctionOp;
import org.apache.sysml.hops.FunctionOp.FunctionType;
import org.apache.sysml.hops.Hop;
import org.apache.sysml.hops.Hop.DataGenMethod;
import org.apache.sysml.hops.Hop.DataOpTypes;
import org.apache.sysml.hops.Hop.OpOp2;
import org.apache.sysml.hops.HopsException;
import org.apache.sysml.hops.OptimizerUtils;
import org.apache.sysml.hops.Hop.VisitStatus;
import org.apache.sysml.hops.LiteralOp;
import org.apache.sysml.hops.rewrite.HopRewriteUtils;
import org.apache.sysml.hops.recompile.Recompiler;
import org.apache.sysml.parser.DMLProgram;
import org.apache.sysml.parser.DMLTranslator;
import org.apache.sysml.parser.DataIdentifier;
import org.apache.sysml.parser.Expression.DataType;
import org.apache.sysml.parser.Expression.ValueType;
import org.apache.sysml.parser.ExternalFunctionStatement;
import org.apache.sysml.parser.ForStatement;
import org.apache.sysml.parser.ForStatementBlock;
import org.apache.sysml.parser.FunctionStatement;
import org.apache.sysml.parser.FunctionStatementBlock;
import org.apache.sysml.parser.IfStatement;
import org.apache.sysml.parser.IfStatementBlock;
import org.apache.sysml.parser.LanguageException;
import org.apache.sysml.parser.ParseException;
import org.apache.sysml.parser.StatementBlock;
import org.apache.sysml.parser.WhileStatement;
import org.apache.sysml.parser.WhileStatementBlock;
import org.apache.sysml.runtime.controlprogram.LocalVariableMap;
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysml.runtime.instructions.cp.BooleanObject;
import org.apache.sysml.runtime.instructions.cp.Data;
import org.apache.sysml.runtime.instructions.cp.DoubleObject;
import org.apache.sysml.runtime.instructions.cp.IntObject;
import org.apache.sysml.runtime.instructions.cp.ScalarObject;
import org.apache.sysml.runtime.instructions.cp.StringObject;
import org.apache.sysml.runtime.matrix.MatrixCharacteristics;
import org.apache.sysml.runtime.matrix.MatrixFormatMetaData;
import org.apache.sysml.udf.lib.DeNaNWrapper;
import org.apache.sysml.udf.lib.DeNegInfinityWrapper;
import org.apache.sysml.udf.lib.DynamicReadMatrixCP;
import org.apache.sysml.udf.lib.DynamicReadMatrixRcCP;
import org.apache.sysml.udf.lib.OrderWrapper;

/**
 * This Inter Procedural Analysis (IPA) serves two major purposes:
 *   1) Inter-Procedure Analysis: propagate statistics from calling program into 
 *      functions and back into main program. This is done recursively for nested 
 *      function invocations.
 *   2) Intra-Procedural Analysis: propagate statistics across hop dags of subsequent 
 *      statement blocks in order to allow chained function calls and reasoning about
 *      changing sparsity etc (that requires the rewritten hops dag as input). This 
 *      also includes control-flow aware propagation of size and sparsity. Furthermore,
 *      it also serves as a second constant propagation pass.
 * 
 * In general, the basic concepts of IPA are as follows and all places that deal with
 * statistic propagation should adhere to that:
 *   * Rule 1: Exact size propagation: Since the dimension information are sometimes used
 *     for specific lops construction (e.g., in append) and rewrites, we cannot propagate worst-case 
 *     estimates but only exact information; otherwise size must be unknown.
 *   * Rule 2: Dimension information and sparsity are handled separately, i.e., if an updated 
 *     variable has changing sparsity but constant dimensions, its dimensions are known but
 *     sparsity unknown.
 * 
 * More specifically, those two rules are currently realized as follows:
 *   * Statistics propagation is applied for DML-bodied functions that are invoked exactly once.
 *     This ensures that we can savely propagate exact information into this function.
 *     If ALLOW_MULTIPLE_FUNCTION_CALLS is enabled we treat multiple calls with the same sizes
 *     as one call and hence, propagate those statistics into the function as well.
 *   * Output size inference happens for DML-bodied functions that are invoked exactly once
 *     and for external functions that are known in advance (see UDFs in org.apache.sysml.udf).
 *   * Size propagation across DAGs requires control flow awareness:
 *     - Generic statement blocks: updated variables -> old stats in; new stats out
 *     - While/for statement blocks: updated variables -> old stats in/out if loop insensitive; otherwise unknown
 *     - If statement blocks: updated variables -> old stats in; new stats out if branch-insensitive            
 *     
 *         
 */
@SuppressWarnings("deprecation")
public class InterProceduralAnalysis 
{
	
	private static final boolean LDEBUG = false; //internal local debug level
	private static final Log LOG = LogFactory.getLog(InterProceduralAnalysis.class.getName());
    
	//internal configuration parameters
	private static final boolean INTRA_PROCEDURAL_ANALYSIS      = true; //propagate statistics across statement blocks (main/functions)	
	private static final boolean PROPAGATE_KNOWN_UDF_STATISTICS = true; //propagate statistics for known external functions 
	private static final boolean ALLOW_MULTIPLE_FUNCTION_CALLS  = true; //propagate consistent statistics from multiple calls 
	private static final boolean REMOVE_UNUSED_FUNCTIONS        = true; //remove unused functions (inlined or never called)
	private static final boolean FLAG_FUNCTION_RECOMPILE_ONCE   = true; //flag functions which require recompilation inside a loop for full function recompile
	private static final boolean REMOVE_UNNECESSARY_CHECKPOINTS = true; //remove unnecessary checkpoints (unconditionally overwritten intermediates) 
	private static final boolean REMOVE_CONSTANT_BINARY_OPS     = true; //remove constant binary operations (e.g., X*ones, where ones=matrix(1,...)) 
	
	static {
		// for internal debugging only
		if( LDEBUG ) {
			Logger.getLogger("org.apache.sysml.hops.ipa.InterProceduralAnalysis")
				  .setLevel((Level) Level.DEBUG);
		}
	}
	
	public InterProceduralAnalysis()
	{
		//do nothing
	}
	
	/**
	 * Public interface of IPA - everything else is meant for internal use only.
	 * 
	 * @param dmlt
	 * @param dmlp
	 * @throws HopsException
	 * @throws ParseException
	 * @throws LanguageException
	 */
	public void analyzeProgram( DMLProgram dmlp ) 
		throws HopsException, ParseException, LanguageException
	{
		//step 1: get candidates for statistics propagation into functions (if required)
		Map<String, Integer> fcandCounts = new HashMap<String, Integer>();
		Map<String, FunctionOp> fcandHops = new HashMap<String, FunctionOp>();
		Map<String, Set<Long>> fcandSafeNNZ = new HashMap<String, Set<Long>>(); 
		Set<String> allFCandKeys = new HashSet<String>();
		if( dmlp.getFunctionStatementBlocks().size() > 0 )
		{
			for ( StatementBlock sb : dmlp.getStatementBlocks() ) //get candidates (over entire program)
				getFunctionCandidatesForStatisticPropagation( sb, fcandCounts, fcandHops );
			allFCandKeys.addAll(fcandCounts.keySet()); //cp before pruning
			pruneFunctionCandidatesForStatisticPropagation( fcandCounts, fcandHops );	
			determineFunctionCandidatesNNZPropagation( fcandHops, fcandSafeNNZ );
			DMLTranslator.resetHopsDAGVisitStatus( dmlp );
		}
		
		if( !fcandCounts.isEmpty() || INTRA_PROCEDURAL_ANALYSIS ) {
			//step 2: propagate statistics into functions and across DAGs
			//(callVars used to chain outputs/inputs of multiple functions calls) 
			LocalVariableMap callVars = new LocalVariableMap();
			for ( StatementBlock sb : dmlp.getStatementBlocks() ) //propagate stats into candidates
				propagateStatisticsAcrossBlock( sb, fcandCounts.keySet(), callVars, fcandSafeNNZ, new HashSet<String>() );
		}
		
		//step 3: remove unused functions (e.g., inlined or never called)
		if( REMOVE_UNUSED_FUNCTIONS ) {
			removeUnusedFunctions( dmlp, allFCandKeys );
		}
		
		//step 4: flag functions with loops for 'recompile-on-entry'
		if( FLAG_FUNCTION_RECOMPILE_ONCE ) {
			flagFunctionsForRecompileOnce( dmlp );
		}
		
		//step 5: set global data flow properties
		if( REMOVE_UNNECESSARY_CHECKPOINTS 
			&& OptimizerUtils.isSparkExecutionMode() )
		{
			removeUnnecessaryCheckpoints(dmlp);
		}
		
		//step 6: remove constant binary ops
		if( REMOVE_CONSTANT_BINARY_OPS ) {
			removeConstantBinaryOps(dmlp);
		}
	}
	
	/**
	 * 
	 * @param sb
	 * @return
	 * @throws ParseException 
	 * @throws HopsException 
	 */
	public Set<String> analyzeSubProgram( StatementBlock sb ) 
		throws HopsException, ParseException
	{
		DMLTranslator.resetHopsDAGVisitStatus(sb);
		
		//step 1: get candidates for statistics propagation into functions (if required)
		Map<String, Integer> fcandCounts = new HashMap<String, Integer>();
		Map<String, FunctionOp> fcandHops = new HashMap<String, FunctionOp>();
		Map<String, Set<Long>> fcandSafeNNZ = new HashMap<String, Set<Long>>(); 
		Set<String> allFCandKeys = new HashSet<String>();
		getFunctionCandidatesForStatisticPropagation( sb, fcandCounts, fcandHops );
		allFCandKeys.addAll(fcandCounts.keySet()); //cp before pruning
		pruneFunctionCandidatesForStatisticPropagation( fcandCounts, fcandHops );	
		determineFunctionCandidatesNNZPropagation( fcandHops, fcandSafeNNZ );
		DMLTranslator.resetHopsDAGVisitStatus( sb );
		
		if( !fcandCounts.isEmpty() ) {
			//step 2: propagate statistics into functions and across DAGs
			//(callVars used to chain outputs/inputs of multiple functions calls) 
			LocalVariableMap callVars = new LocalVariableMap();
			propagateStatisticsAcrossBlock( sb, fcandCounts.keySet(), callVars, fcandSafeNNZ, new HashSet<String>() );
		}
		
		return fcandCounts.keySet();
	}
	
	
	/////////////////////////////
	// GET FUNCTION CANDIDATES
	//////
	
	/**
	 * 
	 * @param sb
	 * @param fcand
	 * @throws HopsException
	 * @throws ParseException
	 */
	private void getFunctionCandidatesForStatisticPropagation( StatementBlock sb, Map<String, Integer> fcandCounts, Map<String, FunctionOp> fcandHops ) 
		throws HopsException, ParseException
	{
		if (sb instanceof FunctionStatementBlock)
		{
			FunctionStatementBlock fsb = (FunctionStatementBlock)sb;
			FunctionStatement fstmt = (FunctionStatement)fsb.getStatement(0);
			for (StatementBlock sbi : fstmt.getBody())
				getFunctionCandidatesForStatisticPropagation(sbi, fcandCounts, fcandHops);
		}
		else if (sb instanceof WhileStatementBlock)
		{
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement)wsb.getStatement(0);
			for (StatementBlock sbi : wstmt.getBody())
				getFunctionCandidatesForStatisticPropagation(sbi, fcandCounts, fcandHops);
		}	
		else if (sb instanceof IfStatementBlock)
		{
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement)isb.getStatement(0);
			for (StatementBlock sbi : istmt.getIfBody())
				getFunctionCandidatesForStatisticPropagation(sbi, fcandCounts, fcandHops);
			for (StatementBlock sbi : istmt.getElseBody())
				getFunctionCandidatesForStatisticPropagation(sbi, fcandCounts, fcandHops);
		}
		else if (sb instanceof ForStatementBlock) //incl parfor
		{
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement)fsb.getStatement(0);
			for (StatementBlock sbi : fstmt.getBody())
				getFunctionCandidatesForStatisticPropagation(sbi, fcandCounts, fcandHops);
		}
		else //generic (last-level)
		{
			ArrayList<Hop> roots = sb.get_hops();
			if( roots != null ) //empty statement blocks
				for( Hop root : roots )
					getFunctionCandidatesForStatisticPropagation(sb.getDMLProg(), root, fcandCounts, fcandHops);
		}
	}
	
	/**
	 * 
	 * @param prog
	 * @param hop
	 * @param fcand
	 * @throws HopsException
	 * @throws ParseException
	 */
	private void getFunctionCandidatesForStatisticPropagation(DMLProgram prog, Hop hop, Map<String, Integer> fcandCounts, Map<String, FunctionOp> fcandHops ) 
		throws HopsException, ParseException
	{
		if( hop.getVisited() == VisitStatus.DONE )
			return;
		
		if( hop instanceof FunctionOp && !((FunctionOp)hop).getFunctionNamespace().equals(DMLProgram.INTERNAL_NAMESPACE) )
		{
			//maintain counters and investigate functions if not seen so far
			FunctionOp fop = (FunctionOp) hop;
			String fkey = DMLProgram.constructFunctionKey(fop.getFunctionNamespace(), fop.getFunctionName());
			
			if( fcandCounts.containsKey(fkey) ) {
				if( ALLOW_MULTIPLE_FUNCTION_CALLS )
				{
					//compare input matrix characteristics for both function calls
					//(if unknown or difference: maintain counter - this function is no candidate)
					boolean consistent = true;
					FunctionOp efop = fcandHops.get(fkey);
					int numInputs = efop.getInput().size();
					for( int i=0; i<numInputs; i++ )
					{
						Hop h1 = efop.getInput().get(i);
						Hop h2 = fop.getInput().get(i);
						//check matrix and scalar sizes (if known dims, nnz known/unknown, 
						// safeness of nnz propagation, determined later per input)
						consistent &= (h1.dimsKnown() && h2.dimsKnown()
								   &&  h1.getDim1()==h2.getDim1() 
								   &&  h1.getDim2()==h2.getDim2()
								   &&  h1.getNnz()==h2.getNnz() );
						//check literal values (equi value)
						if( h1 instanceof LiteralOp ){
							consistent &= (h2 instanceof LiteralOp 
									      && HopRewriteUtils.isEqualValue((LiteralOp)h1, (LiteralOp)h2));
						}
						
						
					}
					
					if( !consistent ) //if differences, do not propagate
						fcandCounts.put(fkey, fcandCounts.get(fkey)+1);
				}
				else
				{
					//maintain counter (this function is no candidate)
					fcandCounts.put(fkey, fcandCounts.get(fkey)+1);
				}
			}
			else { //first appearance
				fcandCounts.put(fkey, 1); //create a new count entry
				fcandHops.put(fkey, fop); //keep the function call hop
				FunctionStatementBlock fsb = prog.getFunctionStatementBlock(fop.getFunctionNamespace(), fop.getFunctionName());
				getFunctionCandidatesForStatisticPropagation(fsb, fcandCounts, fcandHops);
			}
		}
			
		for( Hop c : hop.getInput() )
			getFunctionCandidatesForStatisticPropagation(prog, c, fcandCounts, fcandHops);
		
		hop.setVisited(VisitStatus.DONE);
	}
	
	/**
	 * 
	 * @param fcand
	 */
	private void pruneFunctionCandidatesForStatisticPropagation(Map<String, Integer> fcandCounts, Map<String, FunctionOp> fcandHops)
	{
		//debug input
		if( LOG.isDebugEnabled() )
			for( Entry<String,Integer> e : fcandCounts.entrySet() )
			{
				String key = e.getKey();
				Integer count = e.getValue();
				LOG.debug("IPA: FUNC statistic propagation candidate: "+key+", callCount="+count);
			}
		
		//materialize key set
		Set<String> tmp = new HashSet<String>(fcandCounts.keySet());
		
		//check and prune candidate list
		for( String key : tmp )
		{
			Integer cnt = fcandCounts.get(key);
			if( cnt != null && cnt > 1 ) //if multiple refs
				fcandCounts.remove(key);
		}
		
		//debug output
		if( LOG.isDebugEnabled() )
			for( String key : fcandCounts.keySet() )
			{
				LOG.debug("IPA: FUNC statistic propagation candidate (after pruning): "+key);
			}
	}

	/////////////////////////////
	// DETERMINE NNZ PROPAGATE SAFENESS
	//////

	/**
	 * Populates fcandSafeNNZ with all <functionKey,hopID> pairs where it is safe to
	 * propagate nnz into the function.
	 *  
	 * @param fcandHops
	 * @param fcandSafeNNZ
	 */
	private void determineFunctionCandidatesNNZPropagation(Map<String, FunctionOp> fcandHops, Map<String, Set<Long>> fcandSafeNNZ)
	{
		//for all function candidates
		for( Entry<String, FunctionOp> e : fcandHops.entrySet() )
		{
			String fKey = e.getKey();
			FunctionOp fop = e.getValue();
			HashSet<Long> tmp = new HashSet<Long>();
			
			//for all inputs of this function call
			for( Hop input : fop.getInput() )
			{
				//if nnz known it is safe to propagate those nnz because for multiple calls 
				//we checked of equivalence and hence all calls have the same nnz
				if( input.getNnz()>=0 ) 
					tmp.add(input.getHopID());
			}
			
			fcandSafeNNZ.put(fKey, tmp);
		}
	}
	
	/////////////////////////////
	// INTRA-PROCEDURE ANALYSIS
	//////	
	
	/**
	 * 
	 * @param sb
	 * @param fcand
	 * @throws HopsException
	 * @throws ParseException
	 * @throws CloneNotSupportedException 
	 */
	private void propagateStatisticsAcrossBlock( StatementBlock sb, Set<String> fcand, LocalVariableMap callVars, Map<String, Set<Long>> fcandSafeNNZ, Set<String> fnStack ) 
		throws HopsException, ParseException
	{
		if (sb instanceof FunctionStatementBlock)
		{
			FunctionStatementBlock fsb = (FunctionStatementBlock)sb;
			FunctionStatement fstmt = (FunctionStatement)fsb.getStatement(0);
			for (StatementBlock sbi : fstmt.getBody())
				propagateStatisticsAcrossBlock(sbi, fcand, callVars, fcandSafeNNZ, fnStack);
		}
		else if (sb instanceof WhileStatementBlock)
		{
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement)wsb.getStatement(0);
			//old stats into predicate
			propagateStatisticsAcrossPredicateDAG(wsb.getPredicateHops(), callVars);
			//remove updated constant scalars
			Recompiler.removeUpdatedScalars(callVars, wsb);
			//check and propagate stats into body
			LocalVariableMap oldCallVars = (LocalVariableMap) callVars.clone();
			for (StatementBlock sbi : wstmt.getBody())
				propagateStatisticsAcrossBlock(sbi, fcand, callVars, fcandSafeNNZ, fnStack);
			if( Recompiler.reconcileUpdatedCallVarsLoops(oldCallVars, callVars, wsb) ){ //second pass if required
				propagateStatisticsAcrossPredicateDAG(wsb.getPredicateHops(), callVars);
				for (StatementBlock sbi : wstmt.getBody())
					propagateStatisticsAcrossBlock(sbi, fcand, callVars, fcandSafeNNZ, fnStack);
			}
			//remove updated constant scalars
			Recompiler.removeUpdatedScalars(callVars, sb);
		}	
		else if (sb instanceof IfStatementBlock) 
		{
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement)isb.getStatement(0);
			//old stats into predicate
			propagateStatisticsAcrossPredicateDAG(isb.getPredicateHops(), callVars);			
			//check and propagate stats into body
			LocalVariableMap oldCallVars = (LocalVariableMap) callVars.clone();
			LocalVariableMap callVarsElse = (LocalVariableMap) callVars.clone();
			for (StatementBlock sbi : istmt.getIfBody())
				propagateStatisticsAcrossBlock(sbi, fcand, callVars, fcandSafeNNZ, fnStack);
			for (StatementBlock sbi : istmt.getElseBody())
				propagateStatisticsAcrossBlock(sbi, fcand, callVarsElse, fcandSafeNNZ, fnStack);
			callVars = Recompiler.reconcileUpdatedCallVarsIf(oldCallVars, callVars, callVarsElse, isb);
			//remove updated constant scalars
			Recompiler.removeUpdatedScalars(callVars, sb);
		}
		else if (sb instanceof ForStatementBlock) //incl parfor
		{
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement)fsb.getStatement(0);
			//old stats into predicate
			propagateStatisticsAcrossPredicateDAG(fsb.getFromHops(), callVars);
			propagateStatisticsAcrossPredicateDAG(fsb.getToHops(), callVars);
			propagateStatisticsAcrossPredicateDAG(fsb.getIncrementHops(), callVars);
			//remove updated constant scalars
			Recompiler.removeUpdatedScalars(callVars, fsb);
			//check and propagate stats into body
			LocalVariableMap oldCallVars = (LocalVariableMap) callVars.clone();
			for (StatementBlock sbi : fstmt.getBody())
				propagateStatisticsAcrossBlock(sbi, fcand, callVars, fcandSafeNNZ, fnStack);
			if( Recompiler.reconcileUpdatedCallVarsLoops(oldCallVars, callVars, fsb) )
				for (StatementBlock sbi : fstmt.getBody())
					propagateStatisticsAcrossBlock(sbi, fcand, callVars, fcandSafeNNZ, fnStack);
			//remove updated constant scalars
			Recompiler.removeUpdatedScalars(callVars, sb);
		}
		else //generic (last-level)
		{	
			//remove updated constant scalars
			Recompiler.removeUpdatedScalars(callVars, sb);
			//old stats in, new stats out if updated
			ArrayList<Hop> roots = sb.get_hops();
			DMLProgram prog = sb.getDMLProg();
			//refresh stats across dag
			Hop.resetVisitStatus(roots);
			propagateStatisticsAcrossDAG(roots, callVars);
			//propagate stats into function calls
			Hop.resetVisitStatus(roots);
			propagateStatisticsIntoFunctions(prog, roots, fcand, callVars, fcandSafeNNZ, fnStack);
		}
	}
	

	/**
	 * 
	 * @param root
	 * @param vars
	 * @throws HopsException
	 */
	private void propagateStatisticsAcrossPredicateDAG( Hop root, LocalVariableMap vars ) 
		throws HopsException
	{
		if( root == null )
			return;
		
		//reset visit status because potentially called multiple times
		root.resetVisitStatus();
		
		try
		{
			Recompiler.rUpdateStatistics( root, vars );
			
			//note: for predicates no output statistics
			//Recompiler.extractDAGOutputStatistics(root, vars);
		}
		catch(Exception ex)
		{
			throw new HopsException("Failed to update Hop DAG statistics.", ex);
		}
	}
	
	
	/**
	 * 
	 * @param roots
	 * @param vars
	 * @throws HopsException
	 */
	private void propagateStatisticsAcrossDAG( ArrayList<Hop> roots, LocalVariableMap vars ) 
		throws HopsException
	{
		if( roots == null )
			return;
		
		try
		{
			//update DAG statistics from leafs to roots
			for( Hop hop : roots )
				Recompiler.rUpdateStatistics( hop, vars );

			//extract statistics from roots
			Recompiler.extractDAGOutputStatistics(roots, vars, true);
		}
		catch( Exception ex )
		{
			throw new HopsException("Failed to update Hop DAG statistics.", ex);
		}
	}
	
	
	/////////////////////////////
	// INTER-PROCEDURE ANALYIS
	//////
	
	
	/**
	 * 
	 * @param prog
	 * @param hop
	 * @param fcand
	 * @param callVars
	 * @throws HopsException
	 * @throws ParseException
	 */
	private void propagateStatisticsIntoFunctions(DMLProgram prog, ArrayList<Hop> roots, Set<String> fcand, LocalVariableMap callVars, Map<String, Set<Long>> fcandSafeNNZ, Set<String> fnStack ) 
			throws HopsException, ParseException
	{
		for( Hop root : roots )
			propagateStatisticsIntoFunctions(prog, root, fcand, callVars, fcandSafeNNZ, fnStack);
	}
	
	
	/**
	 * 
	 * @param prog
	 * @param hop
	 * @param fcand
	 * @throws HopsException
	 * @throws ParseException
	 */
	private void propagateStatisticsIntoFunctions(DMLProgram prog, Hop hop, Set<String> fcand, LocalVariableMap callVars, Map<String, Set<Long>> fcandSafeNNZ, Set<String> fnStack ) 
		throws HopsException, ParseException
	{
		if( hop.getVisited() == VisitStatus.DONE )
			return;
		
		for( Hop c : hop.getInput() )
			propagateStatisticsIntoFunctions(prog, c, fcand, callVars, fcandSafeNNZ, fnStack);
		
		if( hop instanceof FunctionOp )
		{
			//maintain counters and investigate functions if not seen so far
			FunctionOp fop = (FunctionOp) hop;
			String fkey = DMLProgram.constructFunctionKey(fop.getFunctionNamespace(), fop.getFunctionName());
			
			if( fop.getFunctionType() == FunctionType.DML )
			{
				FunctionStatementBlock fsb = prog.getFunctionStatementBlock(fop.getFunctionNamespace(), fop.getFunctionName());
				FunctionStatement fstmt = (FunctionStatement)fsb.getStatement(0);
				
				if(  fcand.contains(fkey) && 
				    !fnStack.contains(fkey)  ) //prevent recursion	
				{
					//maintain function call stack
					fnStack.add(fkey);
					
					//create mapping and populate symbol table for refresh
					LocalVariableMap tmpVars = new LocalVariableMap();
					populateLocalVariableMapForFunctionCall( fstmt, fop, tmpVars, fcandSafeNNZ.get(fkey) );
	
					//recursively propagate statistics
					propagateStatisticsAcrossBlock(fsb, fcand, tmpVars, fcandSafeNNZ, fnStack);
					
					//extract vars from symbol table, re-map and refresh main program
					extractFunctionCallReturnStatistics(fstmt, fop, tmpVars, callVars, true);		
					
					//maintain function call stack
					fnStack.remove(fkey);
				}
				else
				{
					extractFunctionCallUnknownReturnStatistics(fstmt, fop, callVars);
				}
			}
			else if (   fop.getFunctionType() == FunctionType.EXTERNAL_FILE
				     || fop.getFunctionType() == FunctionType.EXTERNAL_MEM  )
			{
				//infer output size for known external functions
				FunctionStatementBlock fsb = prog.getFunctionStatementBlock(fop.getFunctionNamespace(), fop.getFunctionName());
				ExternalFunctionStatement fstmt = (ExternalFunctionStatement) fsb.getStatement(0);
				if( PROPAGATE_KNOWN_UDF_STATISTICS ) 
					extractExternalFunctionCallReturnStatistics(fstmt, fop, callVars);
				else
					extractFunctionCallUnknownReturnStatistics(fstmt, fop, callVars);
			}
		}
		
		hop.setVisited(VisitStatus.DONE);
	}
	
	
	/**
	 * 
	 * @param fstmt
	 * @param fop
	 * @param vars
	 * @throws HopsException 
	 */
	private void populateLocalVariableMapForFunctionCall( FunctionStatement fstmt, FunctionOp fop, LocalVariableMap vars, Set<Long> inputSafeNNZ ) 
		throws HopsException
	{
		ArrayList<DataIdentifier> inputVars = fstmt.getInputParams();
		ArrayList<Hop> inputOps = fop.getInput();
		
		for( int i=0; i<inputVars.size(); i++ )
		{
			//create mapping between input hops and vars
			DataIdentifier dat = inputVars.get(i);
			Hop input = inputOps.get(i);
			
			if( input.getDataType()==DataType.MATRIX )
			{
				//propagate matrix characteristics
				MatrixObject mo = new MatrixObject(ValueType.DOUBLE, null);
				MatrixCharacteristics mc = new MatrixCharacteristics( 
											input.getDim1(), input.getDim2(), 
											ConfigurationManager.getBlocksize(), ConfigurationManager.getBlocksize(),
											inputSafeNNZ.contains(input.getHopID())?input.getNnz():-1 );
				MatrixFormatMetaData meta = new MatrixFormatMetaData(mc,null,null);
				mo.setMetaData(meta);	
				vars.put(dat.getName(), mo);	
			}
			else if( input.getDataType()==DataType.SCALAR 
					&& input instanceof LiteralOp           )
			{
				//propagate literal scalars into functions
				LiteralOp lit = (LiteralOp)input;
				ScalarObject scalar = null;
				switch(input.getValueType())
				{
					case DOUBLE:	scalar = new DoubleObject(lit.getDoubleValue()); break;
					case INT:		scalar = new IntObject(lit.getLongValue()); break;
					case BOOLEAN: 	scalar = new BooleanObject(lit.getBooleanValue()); break;
					case STRING:	scalar = new StringObject(lit.getStringValue()); break;
					default: 
						//do nothing
				}
				vars.put(dat.getName(), scalar);	
			}
			
		}
	}
	
	/**
	 * 
	 * @param fstmt
	 * @param fop
	 * @param tmpVars
	 * @param callVars
	 * @param overwrite
	 * @throws HopsException
	 */
	private void extractFunctionCallReturnStatistics( FunctionStatement fstmt, FunctionOp fop, LocalVariableMap tmpVars, LocalVariableMap callVars, boolean overwrite ) 
		throws HopsException
	{
		ArrayList<DataIdentifier> foutputOps = fstmt.getOutputParams();
		String[] outputVars = fop.getOutputVariableNames();
		String fkey = DMLProgram.constructFunctionKey(fop.getFunctionNamespace(), fop.getFunctionName());
		
		try
		{
			for( int i=0; i<foutputOps.size(); i++ )
			{
				DataIdentifier di = foutputOps.get(i);
				String fvarname = di.getName(); //name in function signature
				String pvarname = outputVars[i]; //name in calling program
				
				if( di.getDataType()==DataType.MATRIX && tmpVars.keySet().contains(fvarname) )
				{
					MatrixObject moIn = (MatrixObject) tmpVars.get(fvarname);
					
					if( !callVars.keySet().contains(pvarname) || overwrite ) //not existing so far
					{
						MatrixObject moOut = createOutputMatrix(moIn.getNumRows(), moIn.getNumColumns(), moIn.getNnz());	
						callVars.put(pvarname, moOut);
					}
					else //already existing: take largest   
					{
						Data dat = callVars.get(pvarname);
						if( dat instanceof MatrixObject )
						{
							MatrixObject moOut = (MatrixObject)dat;
							MatrixCharacteristics mc = moOut.getMatrixCharacteristics();
							if( OptimizerUtils.estimateSizeExactSparsity(mc.getRows(), mc.getCols(), (mc.getNonZeros()>0)?((double)mc.getNonZeros())/mc.getRows()/mc.getCols():1.0)	
							    < OptimizerUtils.estimateSize(moIn.getNumRows(), moIn.getNumColumns()) )
							{
								//update statistics if necessary
								mc.setDimension(moIn.getNumRows(), moIn.getNumColumns());
								mc.setNonZeros(moIn.getNnz());
							}
						}
						
					}
				}
			}
		}
		catch( Exception ex )
		{
			throw new HopsException( "Failed to extract output statistics of function "+fkey+".", ex);
		}
	}
	
	/**
	 * 
	 * @param fstmt
	 * @param fop
	 * @param callVars
	 * @throws HopsException
	 */
	private void extractFunctionCallUnknownReturnStatistics( FunctionStatement fstmt, FunctionOp fop, LocalVariableMap callVars ) 
		throws HopsException
	{
		ArrayList<DataIdentifier> foutputOps = fstmt.getOutputParams();
		String[] outputVars = fop.getOutputVariableNames();
		String fkey = DMLProgram.constructFunctionKey(fop.getFunctionNamespace(), fop.getFunctionName());
		
		try
		{
			for( int i=0; i<foutputOps.size(); i++ )
			{
				DataIdentifier di = foutputOps.get(i);
				String pvarname = outputVars[i]; //name in calling program
				
				if( di.getDataType()==DataType.MATRIX )
				{
					MatrixObject moOut = createOutputMatrix(-1, -1, -1);	
					callVars.put(pvarname, moOut);
				}
			}
		}
		catch( Exception ex )
		{
			throw new HopsException( "Failed to extract output statistics of function "+fkey+".", ex);
		}
	}
	
	/**
	 * 
	 * @param fstmt
	 * @param fop
	 * @param callVars
	 * @throws HopsException
	 */
	private void extractExternalFunctionCallReturnStatistics( ExternalFunctionStatement fstmt, FunctionOp fop, LocalVariableMap callVars ) 
		throws HopsException
	{
		String className = fstmt.getOtherParams().get(ExternalFunctionStatement.CLASS_NAME);

		if(    className.equals(OrderWrapper.class.getName()) 
			|| className.equals(DeNaNWrapper.class.getCanonicalName())
			|| className.equals(DeNegInfinityWrapper.class.getCanonicalName()) )
		{			
			Hop input = fop.getInput().get(0);
			long lnnz = className.equals(OrderWrapper.class.getName()) ? input.getNnz() : -1;
			MatrixObject moOut = createOutputMatrix(input.getDim1(), input.getDim2(),lnnz);
			callVars.put(fop.getOutputVariableNames()[0], moOut);
		}
		else if( className.equals("org.apache.sysml.udf.lib.EigenWrapper") ) 
		//else if( className.equals(EigenWrapper.class.getName()) ) //string ref for build flexibility
		{
			Hop input = fop.getInput().get(0);
			callVars.put(fop.getOutputVariableNames()[0], createOutputMatrix(input.getDim1(), 1, -1));
			callVars.put(fop.getOutputVariableNames()[1], createOutputMatrix(input.getDim1(), input.getDim1(),-1));			
		}
		else if( className.equals("org.apache.sysml.udf.lib.LinearSolverWrapperCP") ) 
		//else if( className.equals(LinearSolverWrapperCP.class.getName()) ) //string ref for build flexibility
		{
			Hop input = fop.getInput().get(1);
			callVars.put(fop.getOutputVariableNames()[0], createOutputMatrix(input.getDim1(), 1, -1));
		}
		else if(   className.equals(DynamicReadMatrixCP.class.getName())
				|| className.equals(DynamicReadMatrixRcCP.class.getName()) ) 
		{
			Hop input1 = fop.getInput().get(1); //rows
			Hop input2 = fop.getInput().get(2); //cols
			if( input1 instanceof LiteralOp && input2 instanceof LiteralOp )
				callVars.put(fop.getOutputVariableNames()[0], createOutputMatrix(((LiteralOp)input1).getLongValue(), 
						                                                         ((LiteralOp)input2).getLongValue(),-1));
		}
		else
		{
			extractFunctionCallUnknownReturnStatistics(fstmt, fop, callVars);
		}
	}
	
	/**
	 * 
	 * @param dim1
	 * @param dim2
	 * @param nnz
	 * @return
	 */
	private MatrixObject createOutputMatrix( long dim1, long dim2, long nnz )
	{
		MatrixObject moOut = new MatrixObject(ValueType.DOUBLE, null);
		MatrixCharacteristics mc = new MatrixCharacteristics( 
									dim1, dim2,
									ConfigurationManager.getBlocksize(), ConfigurationManager.getBlocksize(),
									nnz);
		MatrixFormatMetaData meta = new MatrixFormatMetaData(mc,null,null);
		moOut.setMetaData(meta);
		
		return moOut;
	}
	
	/////////////////////////////
	// REMOVE UNUSED FUNCTIONS
	//////

	/**
	 * 
	 * @param dmlp
	 * @param fcandKeys
	 * @throws LanguageException 
	 */
	public void removeUnusedFunctions( DMLProgram dmlp, Set<String> fcandKeys )
		throws LanguageException
	{
		Set<String> fnamespaces = dmlp.getNamespaces().keySet();
		for( String fnspace : fnamespaces  )
		{
			HashMap<String, FunctionStatementBlock> fsbs = dmlp.getFunctionStatementBlocks(fnspace);
			Iterator<Entry<String, FunctionStatementBlock>> iter = fsbs.entrySet().iterator();
			while( iter.hasNext() )
			{
				Entry<String, FunctionStatementBlock> e = iter.next();
				String fname = e.getKey();
				String fKey = DMLProgram.constructFunctionKey(fnspace, fname);
				//probe function candidates, remove if no candidate
				if( !fcandKeys.contains(fKey) )
					iter.remove();
			}
		}
	}
	
	
	/////////////////////////////
	// FLAG FUNCTIONS FOR RECOMPILE_ONCE
	//////
	
	/**
	 * TODO call it after construct lops
	 * 
	 * @param dmlp
	 * @throws LanguageException 
	 */
	public void flagFunctionsForRecompileOnce( DMLProgram dmlp ) 
		throws LanguageException
	{
		for (String namespaceKey : dmlp.getNamespaces().keySet())
			for (String fname : dmlp.getFunctionStatementBlocks(namespaceKey).keySet())
			{
				FunctionStatementBlock fsblock = dmlp.getFunctionStatementBlock(namespaceKey,fname);
				if( rFlagFunctionForRecompileOnce( fsblock, false ) ) 
				{
					fsblock.setRecompileOnce( true ); 
					LOG.debug("IPA: FUNC flagged for recompile-once: " + DMLProgram.constructFunctionKey(namespaceKey, fname));
				}
			}
	}
	
	/**
	 * Returns true if this statementblock requires recompilation inside a 
	 * loop statement block.
	 * 
	 * 
	 * 
	 * @param sb
	 */
	public boolean rFlagFunctionForRecompileOnce( StatementBlock sb, boolean inLoop )
	{
		boolean ret = false;
		
		if (sb instanceof FunctionStatementBlock)
		{
			FunctionStatementBlock fsb = (FunctionStatementBlock)sb;
			FunctionStatement fstmt = (FunctionStatement)fsb.getStatement(0);
			for( StatementBlock c : fstmt.getBody() )
				ret |= rFlagFunctionForRecompileOnce( c, inLoop );			
		}
		else if (sb instanceof WhileStatementBlock)
		{
			//recompilation information not available at this point
			ret = true;
			
			/*
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement)wsb.getStatement(0);
			ret |= (inLoop && wsb.requiresPredicateRecompilation() );
			for( StatementBlock c : wstmt.getBody() )
				ret |= rFlagFunctionForRecompileOnce( c, true );
			*/
		}
		else if (sb instanceof IfStatementBlock)
		{
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement)isb.getStatement(0);
			ret |= (inLoop && isb.requiresPredicateRecompilation() );
			for( StatementBlock c : istmt.getIfBody() )
				ret |= rFlagFunctionForRecompileOnce( c, inLoop );
			for( StatementBlock c : istmt.getElseBody() )
				ret |= rFlagFunctionForRecompileOnce( c, inLoop );
		}
		else if (sb instanceof ForStatementBlock)
		{
			//recompilation information not available at this point
			ret = true;
			
			/* 
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement)fsb.getStatement(0);
			for( StatementBlock c : fstmt.getBody() )
				ret |= rFlagFunctionForRecompileOnce( c, true );
			*/
		}
		else
		{
			ret |= ( inLoop && sb.requiresRecompilation() );
		}
		
		return ret;
	}
	
	/////////////////////////////
	// REMOVE UNNECESSARY CHECKPOINTS
	//////

	/**
	 * 
	 * @param dmlp
	 * @throws HopsException 
	 */
	private void removeUnnecessaryCheckpoints(DMLProgram dmlp) 
		throws HopsException
	{
		//approach: scan over top-level program (guaranteed to be unconditional),
		//collect checkpoints; determine if used before update; remove first checkpoint
		//on second checkpoint if update in between and not used before update
		
		HashMap<String, Hop> chkpointCand = new HashMap<String, Hop>();
		
		for( StatementBlock sb : dmlp.getStatementBlocks() ) 
		{
			//prune candidates (used before updated)
			Set<String> cands = new HashSet<String>(chkpointCand.keySet());
			for( String cand : cands )
				if( sb.variablesRead().containsVariable(cand) 
					&& !sb.variablesUpdated().containsVariable(cand) ) 
				{	
					//note: variableRead might include false positives due to meta 
					//data operations like nrow(X) or operations removed by rewrites 
					//double check hops on basic blocks; otherwise worst-case
					boolean skipRemove = false;
					if( sb.get_hops() !=null ) {
						Hop.resetVisitStatus(sb.get_hops());
						skipRemove = true;
						for( Hop root : sb.get_hops() )
							skipRemove &= !HopRewriteUtils.rContainsRead(root, cand, false);
					}					
					if( !skipRemove )
						chkpointCand.remove(cand);
				}
			
			//prune candidates (updated in conditional control flow)
			Set<String> cands2 = new HashSet<String>(chkpointCand.keySet());
			if( sb instanceof IfStatementBlock || sb instanceof WhileStatementBlock 
				|| sb instanceof ForStatementBlock )
			{
				for( String cand : cands2 )
					if( sb.variablesUpdated().containsVariable(cand) ) {
						chkpointCand.remove(cand);
					}
			}
			//prune candidates (updated w/ multiple reads) 
			else
			{
				for( String cand : cands2 )
					if( sb.variablesUpdated().containsVariable(cand) && sb.get_hops() != null) 
					{
						ArrayList<Hop> hops = sb.get_hops();
						Hop.resetVisitStatus(hops);
						for( Hop root : hops )
							if( root.getName().equals(cand) &&
								!HopRewriteUtils.rHasSimpleReadChain(root, cand) ) {
								chkpointCand.remove(cand);
							}
					}	
			}
		
			//collect checkpoints and remove unnecessary checkpoints
			ArrayList<Hop> tmp = collectCheckpoints(sb.get_hops());
			for( Hop chkpoint : tmp ) {
				if( chkpointCand.containsKey(chkpoint.getName()) ) {
					chkpointCand.get(chkpoint.getName()).setRequiresCheckpoint(false);		
				}
				chkpointCand.put(chkpoint.getName(), chkpoint);
			}
			
		}
	}
	
	/**
	 * 
	 * @param roots
	 * @return
	 */
	private ArrayList<Hop> collectCheckpoints(ArrayList<Hop> roots)
	{
		ArrayList<Hop> ret = new ArrayList<Hop>();	
		if( roots != null ) {
			Hop.resetVisitStatus(roots);
			for( Hop root : roots )
				rCollectCheckpoints(root, ret);
		}
		
		return ret;
	}
	
	/**
	 * 
	 * @param hop
	 * @param checkpoints
	 */
	private void rCollectCheckpoints(Hop hop, ArrayList<Hop> checkpoints)
	{
		if( hop.getVisited()==VisitStatus.DONE )
			return;

		//handle leaf node for variable (checkpoint directly bound
		//to logical variable name and not used)
		if( hop.requiresCheckpoint() && hop.getParent().size()==1 
			&& hop.getParent().get(0) instanceof DataOp
			&& ((DataOp)hop.getParent().get(0)).getDataOpType()==DataOpTypes.TRANSIENTWRITE)
		{
			checkpoints.add(hop);
		}
		
		//recursively process child nodes
		for( Hop c : hop.getInput() )
			rCollectCheckpoints(c, checkpoints);
	
		hop.setVisited(Hop.VisitStatus.DONE);
	}
	
	/////////////////////////////
	// REMOVE CONSTANT BINARY OPS
	//////

	/**
	 * 
	 * @param dmlp
	 * @throws HopsException 
	 */
	private void removeConstantBinaryOps(DMLProgram dmlp) 
		throws HopsException
	{
		//approach: scan over top-level program (guaranteed to be unconditional),
		//collect ones=matrix(1,...); remove b(*)ones if not outer operation		
		HashMap<String, Hop> mOnes = new HashMap<String, Hop>();
		
		for( StatementBlock sb : dmlp.getStatementBlocks() ) 
		{
			//pruning updated variables
			for( String var : sb.variablesUpdated().getVariableNames() )
				if( mOnes.containsKey( var ) )
					mOnes.remove( var );
			
			//replace constant binary ops
			if( !mOnes.isEmpty() )
				rRemoveConstantBinaryOp(sb, mOnes);
			
			//collect matrices of ones from last-level statement blocks
			if( !(sb instanceof IfStatementBlock || sb instanceof WhileStatementBlock 
				  || sb instanceof ForStatementBlock) )
			{
				collectMatrixOfOnes(sb.get_hops(), mOnes);
			}
		}
	}
	
	/**
	 * 
	 * @param roots
	 * @param mOnes
	 */
	private void collectMatrixOfOnes(ArrayList<Hop> roots, HashMap<String,Hop> mOnes)
	{
		if( roots == null )
			return;
		
		for( Hop root : roots )
			if( root instanceof DataOp && ((DataOp)root).getDataOpType()==DataOpTypes.TRANSIENTWRITE
			   && root.getInput().get(0) instanceof DataGenOp
			   && ((DataGenOp)root.getInput().get(0)).getOp()==DataGenMethod.RAND
			   && ((DataGenOp)root.getInput().get(0)).hasConstantValue(1.0)) 
			{
				mOnes.put(root.getName(),root.getInput().get(0));
			}
	}
	
	/**
	 * 
	 * @param sb
	 * @param mOnes
	 * @throws HopsException 
	 */
	private void rRemoveConstantBinaryOp(StatementBlock sb, HashMap<String,Hop> mOnes) 
		throws HopsException
	{
		if( sb instanceof IfStatementBlock )
		{
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement)isb.getStatement(0);
			for( StatementBlock c : istmt.getIfBody() )
				rRemoveConstantBinaryOp(c, mOnes);
			if( istmt.getElseBody() != null )
				for( StatementBlock c : istmt.getElseBody() )
					rRemoveConstantBinaryOp(c, mOnes);	
		}
		else if( sb instanceof WhileStatementBlock )
		{
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement)wsb.getStatement(0);
			for( StatementBlock c : wstmt.getBody() )
				rRemoveConstantBinaryOp(c, mOnes);
		}
		else if( sb instanceof ForStatementBlock )
		{
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement)fsb.getStatement(0);
			for( StatementBlock c : fstmt.getBody() )
				rRemoveConstantBinaryOp(c, mOnes);	
		}
		else
		{
			if( sb.get_hops() != null ){
				Hop.resetVisitStatus(sb.get_hops());
				for( Hop hop : sb.get_hops() )
					rRemoveConstantBinaryOp(hop, mOnes);
			}
		}
	}
	
	/**
	 * 
	 * @param hop
	 * @param mOnes
	 */
	private void rRemoveConstantBinaryOp(Hop hop, HashMap<String,Hop> mOnes)
	{
		if( hop.getVisited()==VisitStatus.DONE )
			return;

		if( hop instanceof BinaryOp && ((BinaryOp)hop).getOp()==OpOp2.MULT
			&& !((BinaryOp) hop).isOuterVectorOperator()
			&& hop.getInput().get(0).getDataType()==DataType.MATRIX
			&& hop.getInput().get(1) instanceof DataOp
			&& mOnes.containsKey(hop.getInput().get(1).getName()) )
		{
			//replace matrix of ones with literal 1 (later on removed by
			//algebraic simplification rewrites; otherwise more complex
			//recursive processing of childs and rewiring required)
			HopRewriteUtils.removeChildReferenceByPos(hop, hop.getInput().get(1), 1);
			HopRewriteUtils.addChildReference(hop, new LiteralOp(1), 1);
		}
		
		//recursively process child nodes
		for( Hop c : hop.getInput() )
			rRemoveConstantBinaryOp(c, mOnes);
	
		hop.setVisited(Hop.VisitStatus.DONE);		
	}
}
