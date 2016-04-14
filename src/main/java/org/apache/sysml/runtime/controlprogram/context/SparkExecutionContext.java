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

package org.apache.sysml.runtime.controlprogram.context;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.storage.RDDInfo;
import org.apache.spark.storage.StorageLevel;

import scala.Tuple2;

import org.apache.sysml.api.DMLScript;
import org.apache.sysml.api.MLContext;
import org.apache.sysml.api.MLContextProxy;
import org.apache.sysml.hops.OptimizerUtils;
import org.apache.sysml.lops.Checkpoint;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.Program;
import org.apache.sysml.runtime.controlprogram.caching.FrameObject;
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysml.runtime.controlprogram.parfor.stat.InfrastructureAnalyzer;
import org.apache.sysml.runtime.instructions.spark.CheckpointSPInstruction;
import org.apache.sysml.runtime.instructions.spark.SPInstruction;
import org.apache.sysml.runtime.instructions.spark.data.BlockPartitioner;
import org.apache.sysml.runtime.instructions.spark.data.BroadcastObject;
import org.apache.sysml.runtime.instructions.spark.data.LineageObject;
import org.apache.sysml.runtime.instructions.spark.data.PartitionedBroadcastMatrix;
import org.apache.sysml.runtime.instructions.spark.data.PartitionedMatrixBlock;
import org.apache.sysml.runtime.instructions.spark.data.RDDObject;
import org.apache.sysml.runtime.instructions.spark.functions.CopyBinaryCellFunction;
import org.apache.sysml.runtime.instructions.spark.functions.CopyBlockPairFunction;
import org.apache.sysml.runtime.instructions.spark.functions.CopyTextInputFunction;
import org.apache.sysml.runtime.instructions.spark.functions.CreateSparseBlockFunction;
import org.apache.sysml.runtime.instructions.spark.utils.RDDAggregateUtils;
import org.apache.sysml.runtime.instructions.spark.utils.SparkUtils;
import org.apache.sysml.runtime.matrix.MatrixCharacteristics;
import org.apache.sysml.runtime.matrix.data.InputInfo;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.MatrixCell;
import org.apache.sysml.runtime.matrix.data.MatrixIndexes;
import org.apache.sysml.runtime.matrix.data.OutputInfo;
import org.apache.sysml.runtime.matrix.data.SparseBlock;
import org.apache.sysml.runtime.matrix.mapred.MRJobConfiguration;
import org.apache.sysml.runtime.util.MapReduceTool;
import org.apache.sysml.utils.Statistics;


public class SparkExecutionContext extends ExecutionContext
{

	private static final Log LOG = LogFactory.getLog(SparkExecutionContext.class.getName());

	//internal configurations 
	private static boolean LAZY_SPARKCTX_CREATION = true;
	private static boolean ASYNCHRONOUS_VAR_DESTROY = true;
	private static boolean FAIR_SCHEDULER_MODE = true;
	
	//executor memory and relative fractions as obtained from the spark configuration
	private static long _memExecutors = -1; //mem per executors
	private static double _memRatioData = -1; 
	private static double _memRatioShuffle = -1;
	private static int _numExecutors = -1; //total executors
	private static int _defaultPar = -1; //total vcores  
	private static boolean _confOnly = false; //infrastructure info based on config
	
	// Only one SparkContext may be active per JVM. You must stop() the active SparkContext before creating a new one. 
	// This limitation may eventually be removed; see SPARK-2243 for more details.
	private static JavaSparkContext _spctx = null; 
	
	protected SparkExecutionContext(Program prog) 
	{
		//protected constructor to force use of ExecutionContextFactory
		this( true, prog );
	}

	protected SparkExecutionContext(boolean allocateVars, Program prog) 
	{
		//protected constructor to force use of ExecutionContextFactory
		super( allocateVars, prog );
				
		//spark context creation via internal initializer
		if( !(LAZY_SPARKCTX_CREATION && OptimizerUtils.isHybridExecutionMode()) ) {
			initSparkContext();
		}
	}
		
	/**
	 * Returns the used singleton spark context. In case of lazy spark context
	 * creation, this methods blocks until the spark context is created.
	 *  
	 * @return
	 */
	public JavaSparkContext getSparkContext()
	{
		//lazy spark context creation on demand (lazy instead of asynchronous 
		//to avoid wait for uninitialized spark context on close)
		if( LAZY_SPARKCTX_CREATION ) {
			initSparkContext();
		}
		
		//return the created spark context
		return _spctx;
	}
	
	/**
	 * 
	 * @return
	 */
	public static JavaSparkContext getSparkContextStatic()
	{
		initSparkContext();
		return _spctx;
	}
	
	/**
	 * 
	 */
	public void close() 
	{
		synchronized( SparkExecutionContext.class ) {
			if( _spctx != null ) 
			{
				//stop the spark context if existing
				_spctx.stop();
				
				//make sure stopped context is never used again
				_spctx = null; 
			}
				
		}
	}
	
	public static boolean isLazySparkContextCreation(){
		return LAZY_SPARKCTX_CREATION;
	}
	
	/**
	 * 
	 */
	private synchronized static void initSparkContext()
	{	
		//check for redundant spark context init
		if( _spctx != null )
			return;
	
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		
		//create a default spark context (master, appname, etc refer to system properties
		//as given in the spark configuration or during spark-submit)
		
		MLContext mlCtx = MLContextProxy.getActiveMLContext();
		if(mlCtx != null) 
		{
			// This is when DML is called through spark shell
			// Will clean the passing of static variables later as this involves minimal change to DMLScript
			_spctx = new JavaSparkContext(mlCtx.getSparkContext());
		}
		else 
		{
			if(DMLScript.USE_LOCAL_SPARK_CONFIG) {
				// For now set 4 cores for integration testing :)
				SparkConf conf = new SparkConf().setMaster("local[*]").setAppName("My local integration test app");
				// This is discouraged in spark but have added only for those testcase that cannot stop the context properly
				// conf.set("spark.driver.allowMultipleContexts", "true");
				conf.set("spark.ui.enabled", "false");
				_spctx = new JavaSparkContext(conf);
			}
			else //default cluster setup
			{
				//setup systemml-preferred spark configuration (w/o user choice)
				SparkConf conf = new SparkConf();
				
				//always set unlimited result size (required for cp collect)
				conf.set("spark.driver.maxResultSize", "0");
				
				//always use the fair scheduler (for single jobs, it's equivalent to fifo
				//but for concurrent jobs in parfor it ensures better data locality because
				//round robin assignment mitigates the problem of 'sticky slots')
				if( FAIR_SCHEDULER_MODE ) {
					conf.set("spark.scheduler.mode", "FAIR");
				}
				
				_spctx = new JavaSparkContext(conf);
			}
		}
			
		//globally add binaryblock serialization framework for all hdfs read/write operations
		//TODO if spark context passed in from outside (mlcontext), we need to clean this up at the end 
		if( MRJobConfiguration.USE_BINARYBLOCK_SERIALIZATION )
			MRJobConfiguration.addBinaryBlockSerializationFramework( _spctx.hadoopConfiguration() );
		
		//statistics maintenance
		if( DMLScript.STATISTICS ){
			Statistics.setSparkCtxCreateTime(System.nanoTime()-t0);
		}
	}	
	
	/**
	 * Spark instructions should call this for all matrix inputs except broadcast
	 * variables.
	 * 
	 * @param varname
	 * @return
	 * @throws DMLRuntimeException
	 */
	@SuppressWarnings("unchecked")
	public JavaPairRDD<MatrixIndexes,MatrixBlock> getBinaryBlockRDDHandleForVariable( String varname ) 
		throws DMLRuntimeException 
	{
		return (JavaPairRDD<MatrixIndexes,MatrixBlock>) getRDDHandleForVariable( varname, InputInfo.BinaryBlockInputInfo);
	}
	
	/**
	 * 
	 * @param varname
	 * @param inputInfo
	 * @return
	 * @throws DMLRuntimeException
	 */
	public JavaPairRDD<?,?> getRDDHandleForVariable( String varname, InputInfo inputInfo ) 
		throws DMLRuntimeException
	{
		MatrixObject mo = getMatrixObject(varname);
		return getRDDHandleForMatrixObject(mo, inputInfo);
	}
	
	/**
	 * This call returns an RDD handle for a given matrix object. This includes 
	 * the creation of RDDs for in-memory or binary-block HDFS data. 
	 * 
	 * 
	 * @param varname
	 * @return
	 * @throws DMLRuntimeException  
	 */
	@SuppressWarnings("unchecked")
	public JavaPairRDD<?,?> getRDDHandleForMatrixObject( MatrixObject mo, InputInfo inputInfo ) 
		throws DMLRuntimeException
	{		
		//NOTE: MB this logic should be integrated into MatrixObject
		//However, for now we cannot assume that spark libraries are 
		//always available and hence only store generic references in 
		//matrix object while all the logic is in the SparkExecContext
		
		JavaPairRDD<?,?> rdd = null;
		//CASE 1: rdd already existing (reuse if checkpoint or trigger
		//pending rdd operations if not yet cached but prevent to re-evaluate 
		//rdd operations if already executed and cached
		if(    mo.getRDDHandle()!=null 
			&& (mo.getRDDHandle().isCheckpointRDD() || !mo.isCached(false)) )
		{
			//return existing rdd handling (w/o input format change)
			rdd = mo.getRDDHandle().getRDD();
		}
		//CASE 2: dirty in memory data or cached result of rdd operations
		else if( mo.isDirty() || mo.isCached(false) )
		{
			//get in-memory matrix block and parallelize it
			//w/ guarded parallelize (fallback to export, rdd from file if too large)
			boolean fromFile = false;
			if( !OptimizerUtils.checkSparkCollectMemoryBudget(mo.getMatrixCharacteristics(), 0) ) {
				if( mo.isDirty() ) { //write only if necessary
					mo.exportData();
				}
				rdd = getSparkContext().hadoopFile( mo.getFileName(), inputInfo.inputFormatClass, inputInfo.inputKeyClass, inputInfo.inputValueClass);
				rdd = ((JavaPairRDD<MatrixIndexes, MatrixBlock>)rdd).mapToPair( new CopyBlockPairFunction() ); //cp is workaround for read bug			
				fromFile = true;
			}
			else { //default case
				MatrixBlock mb = mo.acquireRead(); //pin matrix in memory
				rdd = toJavaPairRDD(getSparkContext(), mb, (int)mo.getNumRowsPerBlock(), (int)mo.getNumColumnsPerBlock());
				mo.release(); //unpin matrix
			}
			
			//keep rdd handle for future operations on it
			RDDObject rddhandle = new RDDObject(rdd, mo.getVarName());
			rddhandle.setHDFSFile(fromFile);
			mo.setRDDHandle(rddhandle);
		}
		//CASE 3: non-dirty (file exists on HDFS)
		else
		{
			// parallelize hdfs-resident file
			// For binary block, these are: SequenceFileInputFormat.class, MatrixIndexes.class, MatrixBlock.class
			if(inputInfo == InputInfo.BinaryBlockInputInfo) {
				rdd = getSparkContext().hadoopFile( mo.getFileName(), inputInfo.inputFormatClass, inputInfo.inputKeyClass, inputInfo.inputValueClass);
				//note: this copy is still required in Spark 1.4 because spark hands out whatever the inputformat
				//recordreader returns; the javadoc explicitly recommend to copy all key/value pairs
				rdd = ((JavaPairRDD<MatrixIndexes, MatrixBlock>)rdd).mapToPair( new CopyBlockPairFunction() ); //cp is workaround for read bug
			}
			else if(inputInfo == InputInfo.TextCellInputInfo || inputInfo == InputInfo.CSVInputInfo || inputInfo == InputInfo.MatrixMarketInputInfo) {
				rdd = getSparkContext().hadoopFile( mo.getFileName(), inputInfo.inputFormatClass, inputInfo.inputKeyClass, inputInfo.inputValueClass);
				rdd = ((JavaPairRDD<LongWritable, Text>)rdd).mapToPair( new CopyTextInputFunction() ); //cp is workaround for read bug
			}
			else if(inputInfo == InputInfo.BinaryCellInputInfo) {
				rdd = getSparkContext().hadoopFile( mo.getFileName(), inputInfo.inputFormatClass, inputInfo.inputKeyClass, inputInfo.inputValueClass);
				rdd = ((JavaPairRDD<MatrixIndexes, MatrixCell>)rdd).mapToPair( new CopyBinaryCellFunction() ); //cp is workaround for read bug
			}
			else {
				throw new DMLRuntimeException("Incorrect input format in getRDDHandleForVariable");
			}
			
			//keep rdd handle for future operations on it
			RDDObject rddhandle = new RDDObject(rdd, mo.getVarName());
			rddhandle.setHDFSFile(true);
			mo.setRDDHandle(rddhandle);
		}
		
		return rdd;
	}
	
	/**
	 * FIXME: currently this implementation assumes matrix representations but frame signature
	 * in order to support the old transform implementation.
	 * 
	 * @param mo
	 * @param inputInfo
	 * @return
	 * @throws DMLRuntimeException
	 */
	@SuppressWarnings("unchecked")
	public JavaPairRDD<?,?> getRDDHandleForFrameObject( FrameObject fo, InputInfo inputInfo ) 
		throws DMLRuntimeException
	{		
		//NOTE: MB this logic should be integrated into MatrixObject
		//However, for now we cannot assume that spark libraries are 
		//always available and hence only store generic references in 
		//matrix object while all the logic is in the SparkExecContext
		
		JavaPairRDD<?,?> rdd = null;
		//CASE 1: rdd already existing (reuse if checkpoint or trigger
		//pending rdd operations if not yet cached but prevent to re-evaluate 
		//rdd operations if already executed and cached
		if(    fo.getRDDHandle()!=null 
			&& (fo.getRDDHandle().isCheckpointRDD() || !fo.isCached(false)) )
		{
			//return existing rdd handling (w/o input format change)
			rdd = fo.getRDDHandle().getRDD();
		}
		//CASE 2: dirty in memory data or cached result of rdd operations
		else if( fo.isDirty() || fo.isCached(false) )
		{
			//get in-memory matrix block and parallelize it
			//w/ guarded parallelize (fallback to export, rdd from file if too large)
			boolean fromFile = false;
			if( !OptimizerUtils.checkSparkCollectMemoryBudget(fo.getMatrixCharacteristics(), 0) ) {
				if( fo.isDirty() ) { //write only if necessary
					fo.exportData();
				}
				rdd = getSparkContext().hadoopFile( fo.getFileName(), inputInfo.inputFormatClass, inputInfo.inputKeyClass, inputInfo.inputValueClass);
				rdd = ((JavaPairRDD<MatrixIndexes, MatrixBlock>)rdd).mapToPair( new CopyBlockPairFunction() ); //cp is workaround for read bug			
				fromFile = true;
			}
			else { //default case
				//MatrixBlock mb = mo.acquireRead(); //pin matrix in memory
				//rdd = toJavaPairRDD(getSparkContext(), mb, (int)mo.getNumRowsPerBlock(), (int)mo.getNumColumnsPerBlock());
				//fo.release(); //unpin matrix
				throw new RuntimeException("Not implemented yet.");
			}
			
			//keep rdd handle for future operations on it
			RDDObject rddhandle = new RDDObject(rdd, fo.getVarName());
			rddhandle.setHDFSFile(fromFile);
			fo.setRDDHandle(rddhandle);
		}
		//CASE 3: non-dirty (file exists on HDFS)
		else
		{
			// parallelize hdfs-resident file
			// For binary block, these are: SequenceFileInputFormat.class, MatrixIndexes.class, MatrixBlock.class
			if(inputInfo == InputInfo.BinaryBlockInputInfo) {
				rdd = getSparkContext().hadoopFile( fo.getFileName(), inputInfo.inputFormatClass, inputInfo.inputKeyClass, inputInfo.inputValueClass);
				//note: this copy is still required in Spark 1.4 because spark hands out whatever the inputformat
				//recordreader returns; the javadoc explicitly recommend to copy all key/value pairs
				rdd = ((JavaPairRDD<MatrixIndexes, MatrixBlock>)rdd).mapToPair( new CopyBlockPairFunction() ); //cp is workaround for read bug
			}
			else if(inputInfo == InputInfo.TextCellInputInfo || inputInfo == InputInfo.CSVInputInfo || inputInfo == InputInfo.MatrixMarketInputInfo) {
				rdd = getSparkContext().hadoopFile( fo.getFileName(), inputInfo.inputFormatClass, inputInfo.inputKeyClass, inputInfo.inputValueClass);
				rdd = ((JavaPairRDD<LongWritable, Text>)rdd).mapToPair( new CopyTextInputFunction() ); //cp is workaround for read bug
			}
			else if(inputInfo == InputInfo.BinaryCellInputInfo) {
				rdd = getSparkContext().hadoopFile( fo.getFileName(), inputInfo.inputFormatClass, inputInfo.inputKeyClass, inputInfo.inputValueClass);
				rdd = ((JavaPairRDD<MatrixIndexes, MatrixCell>)rdd).mapToPair( new CopyBinaryCellFunction() ); //cp is workaround for read bug
			}
			else {
				throw new DMLRuntimeException("Incorrect input format in getRDDHandleForVariable");
			}
			
			//keep rdd handle for future operations on it
			RDDObject rddhandle = new RDDObject(rdd, fo.getVarName());
			rddhandle.setHDFSFile(true);
			fo.setRDDHandle(rddhandle);
		}
		
		return rdd;
	}
	
	/**
	 * TODO So far we only create broadcast variables but never destroy
	 * them. This is a memory leak which might lead to executor out-of-memory.
	 * However, in order to handle this, we need to keep track when broadcast 
	 * variables are no longer required.
	 * 
	 * @param varname
	 * @return
	 * @throws DMLRuntimeException
	 */
	@SuppressWarnings("unchecked")
	public PartitionedBroadcastMatrix getBroadcastForVariable( String varname ) 
		throws DMLRuntimeException
	{		
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;

		MatrixObject mo = getMatrixObject(varname);
		
		PartitionedBroadcastMatrix bret = null;
		
		//reuse existing broadcast handle
		if( mo.getBroadcastHandle()!=null 
			&& mo.getBroadcastHandle().isValid() ) 
		{
			bret = mo.getBroadcastHandle().getBroadcast();
		}
		
		//create new broadcast handle (never created, evicted)
		if( bret == null ) 
		{
			//obtain meta data for matrix 
			int brlen = (int) mo.getNumRowsPerBlock();
			int bclen = (int) mo.getNumColumnsPerBlock();
			
			//create partitioned matrix block and release memory consumed by input
			MatrixBlock mb = mo.acquireRead();
			PartitionedMatrixBlock pmb = new PartitionedMatrixBlock(mb, brlen, bclen);
			mo.release();
			
			//determine coarse-grained partitioning
			int numPerPart = PartitionedBroadcastMatrix.computeBlocksPerPartition(mo.getNumRows(), mo.getNumColumns(), brlen, bclen);
			int numParts = (int) Math.ceil((double)pmb.getNumRowBlocks()*pmb.getNumColumnBlocks() / numPerPart); 
			Broadcast<PartitionedMatrixBlock>[] ret = new Broadcast[numParts];
					
			//create coarse-grained partitioned broadcasts
			if( numParts > 1 ) {
				for( int i=0; i<numParts; i++ ) {
					int offset = i * numPerPart;
					int numBlks = Math.min(numPerPart, pmb.getNumRowBlocks()*pmb.getNumColumnBlocks()-offset);
					PartitionedMatrixBlock tmp = pmb.createPartition(offset, numBlks);
					ret[i] = getSparkContext().broadcast(tmp);
				}
			}
			else { //single partition
				ret[0] = getSparkContext().broadcast( pmb);
			}
		
			bret = new PartitionedBroadcastMatrix(ret);
			BroadcastObject bchandle = new BroadcastObject(bret, varname);
			mo.setBroadcastHandle(bchandle);
		}
		
		if (DMLScript.STATISTICS) {
			Statistics.accSparkBroadCastTime(System.nanoTime() - t0);
			Statistics.incSparkBroadcastCount(1);
		}
		
		return bret;
	}
	
	/**
	 * 
	 * @param varname
	 * @return
	 * @throws DMLRuntimeException
	 */
	public BlockPartitioner getPartitionerForRDDVariable(String varname) 
		throws DMLRuntimeException
	{
		//get input rdd and matrix characteristics
		JavaPairRDD<MatrixIndexes,MatrixBlock> in = getBinaryBlockRDDHandleForVariable(varname);
		MatrixCharacteristics mc = getMatrixCharacteristics(varname);
		
		//create tile-based matrix partitioner
		return new BlockPartitioner(mc, in.partitions().size());
	}
	
	/**
	 * Keep the output rdd of spark rdd operations as meta data of matrix objects in the 
	 * symbol table.
	 * 
	 * Spark instructions should call this for all matrix outputs.
	 * 
	 * 
	 * @param varname
	 * @param rdd
	 * @throws DMLRuntimeException 
	 */
	public void setRDDHandleForVariable(String varname, JavaPairRDD<MatrixIndexes,?> rdd) 
		throws DMLRuntimeException
	{
		MatrixObject mo = getMatrixObject(varname);
		RDDObject rddhandle = new RDDObject(rdd, varname);
		mo.setRDDHandle( rddhandle );
	}
	
	/**
	 * Utility method for creating an RDD out of an in-memory matrix block.
	 * 
	 * @param sc
	 * @param block
	 * @return
	 * @throws DMLRuntimeException 
	 */
	public static JavaPairRDD<MatrixIndexes,MatrixBlock> toJavaPairRDD(JavaSparkContext sc, MatrixBlock src, int brlen, int bclen) 
		throws DMLRuntimeException
	{	
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		LinkedList<Tuple2<MatrixIndexes,MatrixBlock>> list = new LinkedList<Tuple2<MatrixIndexes,MatrixBlock>>();
		
		if(    src.getNumRows() <= brlen 
		    && src.getNumColumns() <= bclen )
		{
			list.addLast(new Tuple2<MatrixIndexes,MatrixBlock>(new MatrixIndexes(1,1), src));
		}
		else
		{
			boolean sparse = src.isInSparseFormat();
			
			//create and write subblocks of matrix
			for(int blockRow = 0; blockRow < (int)Math.ceil(src.getNumRows()/(double)brlen); blockRow++)
				for(int blockCol = 0; blockCol < (int)Math.ceil(src.getNumColumns()/(double)bclen); blockCol++)
				{
					int maxRow = (blockRow*brlen + brlen < src.getNumRows()) ? brlen : src.getNumRows() - blockRow*brlen;
					int maxCol = (blockCol*bclen + bclen < src.getNumColumns()) ? bclen : src.getNumColumns() - blockCol*bclen;
					
					MatrixBlock block = new MatrixBlock(maxRow, maxCol, sparse);
						
					int row_offset = blockRow*brlen;
					int col_offset = blockCol*bclen;
	
					//copy submatrix to block
					src.sliceOperations( row_offset, row_offset+maxRow-1, 
							             col_offset, col_offset+maxCol-1, block );							
					
					//append block to sequence file
					MatrixIndexes indexes = new MatrixIndexes(blockRow+1, blockCol+1);
					list.addLast(new Tuple2<MatrixIndexes,MatrixBlock>(indexes, block));
				}
		}
		
		JavaPairRDD<MatrixIndexes,MatrixBlock> result = sc.parallelizePairs(list);
		if (DMLScript.STATISTICS) {
			Statistics.accSparkParallelizeTime(System.nanoTime() - t0);
			Statistics.incSparkParallelizeCount(1);
		}
		
		return result;
	}
	
	/**
	 * This method is a generic abstraction for calls from the buffer pool.
	 * See toMatrixBlock(JavaPairRDD<MatrixIndexes,MatrixBlock> rdd, int numRows, int numCols);
	 * 
	 * @param rdd
	 * @param numRows
	 * @param numCols
	 * @return
	 * @throws DMLRuntimeException 
	 */
	@SuppressWarnings("unchecked")
	public static MatrixBlock toMatrixBlock(RDDObject rdd, int rlen, int clen, int brlen, int bclen, long nnz) 
		throws DMLRuntimeException
	{			
		return toMatrixBlock(
				(JavaPairRDD<MatrixIndexes, MatrixBlock>) rdd.getRDD(), 
				rlen, clen, brlen, bclen, nnz);
	}
	
	/**
	 * Utility method for creating a single matrix block out of a binary block RDD. 
	 * Note that this collect call might trigger execution of any pending transformations. 
	 * 
	 * NOTE: This is an unguarded utility function, which requires memory for both the output matrix
	 * and its collected, blocked representation.
	 * 
	 * @param rdd
	 * @param numRows
	 * @param numCols
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static MatrixBlock toMatrixBlock(JavaPairRDD<MatrixIndexes,MatrixBlock> rdd, int rlen, int clen, int brlen, int bclen, long nnz) 
		throws DMLRuntimeException
	{
		
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;

		MatrixBlock out = null;
		
		if( rlen <= brlen && clen <= bclen ) //SINGLE BLOCK
		{
			//special case without copy and nnz maintenance
			List<Tuple2<MatrixIndexes,MatrixBlock>> list = rdd.collect();
			
			if( list.size()>1 )
				throw new DMLRuntimeException("Expecting no more than one result block.");
			else if( list.size()==1 )
				out = list.get(0)._2();
			else //empty (e.g., after ops w/ outputEmpty=false)
				out = new MatrixBlock(rlen, clen, true);
		}
		else //MULTIPLE BLOCKS
		{
			//determine target sparse/dense representation
			long lnnz = (nnz >= 0) ? nnz : (long)rlen * clen;
			boolean sparse = MatrixBlock.evalSparseFormatInMemory(rlen, clen, lnnz);
						
			//create output matrix block (w/ lazy allocation)
			out = new MatrixBlock(rlen, clen, sparse);
			
			List<Tuple2<MatrixIndexes,MatrixBlock>> list = rdd.collect();
			
			//copy blocks one-at-a-time into output matrix block
			for( Tuple2<MatrixIndexes,MatrixBlock> keyval : list )
			{
				//unpack index-block pair
				MatrixIndexes ix = keyval._1();
				MatrixBlock block = keyval._2();
				
				//compute row/column block offsets
				int row_offset = (int)(ix.getRowIndex()-1)*brlen;
				int col_offset = (int)(ix.getColumnIndex()-1)*bclen;
				int rows = block.getNumRows();
				int cols = block.getNumColumns();
				
				if( sparse ) { //SPARSE OUTPUT
					//append block to sparse target in order to avoid shifting
					//note: this append requires a final sort of sparse rows
					out.appendToSparse(block, row_offset, col_offset);
				}
				else { //DENSE OUTPUT
					out.copy( row_offset, row_offset+rows-1, 
							  col_offset, col_offset+cols-1, block, false );	
				}
			}
			
			//post-processing output matrix
			if( sparse )
				out.sortSparseRows();
			out.recomputeNonZeros();
			out.examSparsity();
		}
		
		if (DMLScript.STATISTICS) {
			Statistics.accSparkCollectTime(System.nanoTime() - t0);
			Statistics.incSparkCollectCount(1);
		}
		
		return out;
	}
	
	@SuppressWarnings("unchecked")
	public static MatrixBlock toMatrixBlock(RDDObject rdd, int rlen, int clen, long nnz) 
		throws DMLRuntimeException
	{			
		return toMatrixBlock(
				(JavaPairRDD<MatrixIndexes, MatrixCell>) rdd.getRDD(), 
				rlen, clen, nnz);
	}
	
	/**
	 * Utility method for creating a single matrix block out of a binary cell RDD. 
	 * Note that this collect call might trigger execution of any pending transformations. 
	 * 
	 * @param rdd
	 * @param rlen
	 * @param clen
	 * @param nnz
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static MatrixBlock toMatrixBlock(JavaPairRDD<MatrixIndexes, MatrixCell> rdd, int rlen, int clen, long nnz) 
		throws DMLRuntimeException
	{	
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;

		MatrixBlock out = null;
		
		//determine target sparse/dense representation
		long lnnz = (nnz >= 0) ? nnz : (long)rlen * clen;
		boolean sparse = MatrixBlock.evalSparseFormatInMemory(rlen, clen, lnnz);
				
		//create output matrix block (w/ lazy allocation)
		out = new MatrixBlock(rlen, clen, sparse);
		
		List<Tuple2<MatrixIndexes,MatrixCell>> list = rdd.collect();
		
		//copy blocks one-at-a-time into output matrix block
		for( Tuple2<MatrixIndexes,MatrixCell> keyval : list )
		{
			//unpack index-block pair
			MatrixIndexes ix = keyval._1();
			MatrixCell cell = keyval._2();
			
			//append cell to dense/sparse target in order to avoid shifting for sparse
			//note: this append requires a final sort of sparse rows
			out.appendValue((int)ix.getRowIndex()-1, (int)ix.getColumnIndex()-1, cell.getValue());
		}
		
		//post-processing output matrix
		if( sparse )
			out.sortSparseRows();
		out.recomputeNonZeros();
		out.examSparsity();
		
		if (DMLScript.STATISTICS) {
			Statistics.accSparkCollectTime(System.nanoTime() - t0);
			Statistics.incSparkCollectCount(1);
		}
		
		return out;
	}
	
	/**
	 * 
	 * @param rdd
	 * @param rlen
	 * @param clen
	 * @param brlen
	 * @param bclen
	 * @param nnz
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static PartitionedMatrixBlock toPartitionedMatrixBlock(JavaPairRDD<MatrixIndexes,MatrixBlock> rdd, int rlen, int clen, int brlen, int bclen, long nnz) 
		throws DMLRuntimeException
	{
		
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;

		PartitionedMatrixBlock out = new PartitionedMatrixBlock(rlen, clen, brlen, bclen);
		List<Tuple2<MatrixIndexes,MatrixBlock>> list = rdd.collect();
		
		//copy blocks one-at-a-time into output matrix block
		for( Tuple2<MatrixIndexes,MatrixBlock> keyval : list )
		{
			//unpack index-block pair
			MatrixIndexes ix = keyval._1();
			MatrixBlock block = keyval._2();
			out.setMatrixBlock((int)ix.getRowIndex(), (int)ix.getColumnIndex(), block);
		}
		
		if (DMLScript.STATISTICS) {
			Statistics.accSparkCollectTime(System.nanoTime() - t0);
			Statistics.incSparkCollectCount(1);
		}
				
		return out;
	}
	
	/**
	 * 
	 * @param rdd
	 * @param oinfo
	 */
	@SuppressWarnings("unchecked")
	public static long writeRDDtoHDFS( RDDObject rdd, String path, OutputInfo oinfo )
	{
		JavaPairRDD<MatrixIndexes,MatrixBlock> lrdd = (JavaPairRDD<MatrixIndexes, MatrixBlock>) rdd.getRDD();
		
		//recompute nnz 
		long nnz = SparkUtils.computeNNZFromBlocks(lrdd);
		
		//save file is an action which also triggers nnz maintenance
		lrdd.saveAsHadoopFile(path, 
				oinfo.outputKeyClass, 
				oinfo.outputValueClass, 
				oinfo.outputFormatClass);
		
		//return nnz aggregate of all blocks
		return nnz;
	}
	
	
	/**
	 * Returns the available memory budget for broadcast variables in bytes.
	 * In detail, this takes into account the total executor memory as well
	 * as relative ratios for data and shuffle. Note, that this is a conservative
	 * estimate since both data memory and shuffle memory might not be fully
	 * utilized. 
	 * 
	 * @return
	 */
	public static double getBroadcastMemoryBudget()
	{
		if( _memExecutors < 0 || _memRatioData < 0 || _memRatioShuffle < 0 )
			analyzeSparkConfiguation();
		
		//70% of remaining free memory
		double membudget = OptimizerUtils.MEM_UTIL_FACTOR *
			              (  _memExecutors 
			               - _memExecutors*(_memRatioData+_memRatioShuffle) );
		
		return membudget;
	}
	
	/**
	 * 
	 * @return
	 */
	public static double getConfiguredTotalDataMemory() {
		return getConfiguredTotalDataMemory(false);
	}
	
	/**
	 * 
	 * @param refresh
	 * @return
	 */
	public static double getConfiguredTotalDataMemory(boolean refresh)
	{
		if( _memExecutors < 0 || _memRatioData < 0 )
			analyzeSparkConfiguation();
		
		//always get the current num executors on refresh because this might 
		//change if not all executors are initially allocated and it is plan-relevant
		if( refresh && !_confOnly ) {
			JavaSparkContext jsc = getSparkContextStatic();
			int numExec = Math.max(jsc.sc().getExecutorMemoryStatus().size() - 1, 1);
			return _memExecutors * _memRatioData * numExec; 
		}
		else
			return ( _memExecutors * _memRatioData * _numExecutors );
	}
	
	public static int getNumExecutors()
	{
		if( _numExecutors < 0 )
			analyzeSparkConfiguation();
		
		return _numExecutors;
	}
	
	public static int getDefaultParallelism() {
		return getDefaultParallelism(false);
	}
	
	/**
	 * 
	 * @return
	 */
	public static int getDefaultParallelism(boolean refresh) 
	{
		if( _defaultPar < 0 && !refresh )
			analyzeSparkConfiguation();
		
		//always get the current default parallelism on refresh because this might 
		//change if not all executors are initially allocated and it is plan-relevant
		if( refresh && !_confOnly )
			return getSparkContextStatic().defaultParallelism();
		else
			return _defaultPar;
	}
	
	/**
	 * 
	 */
	public static void analyzeSparkConfiguation() 
	{
		SparkConf sconf = new SparkConf();
		
		//parse absolute executor memory
		String tmp = sconf.get("spark.executor.memory", "512m");
		if ( tmp.endsWith("g") || tmp.endsWith("G") )
			_memExecutors = Long.parseLong(tmp.substring(0,tmp.length()-1)) * 1024 * 1024 * 1024;
		else if ( tmp.endsWith("m") || tmp.endsWith("M") )
			_memExecutors = Long.parseLong(tmp.substring(0,tmp.length()-1)) * 1024 * 1024;
		else if( tmp.endsWith("k") || tmp.endsWith("K") )
			_memExecutors = Long.parseLong(tmp.substring(0,tmp.length()-1)) * 1024;
		else 
			_memExecutors = Long.parseLong(tmp.substring(0,tmp.length()-2));
		
		//get data and shuffle memory ratios (defaults not specified in job conf)
		_memRatioData = sconf.getDouble("spark.storage.memoryFraction", 0.6); //default 60%
		_memRatioShuffle = sconf.getDouble("spark.shuffle.memoryFraction", 0.2); //default 20%
		
		int numExecutors = sconf.getInt("spark.executor.instances", -1);
		int numCoresPerExec = sconf.getInt("spark.executor.cores", -1);
		int defaultPar = sconf.getInt("spark.default.parallelism", -1);
		
		if( numExecutors > 1 && (defaultPar > 1 || numCoresPerExec > 1) ) {
			_numExecutors = numExecutors;
			_defaultPar = (defaultPar>1) ? defaultPar : numExecutors * numCoresPerExec;
			_confOnly = true;
		}
		else {
			//get default parallelism (total number of executors and cores)
			//note: spark context provides this information while conf does not
			//(for num executors we need to correct for driver and local mode)
			JavaSparkContext jsc = getSparkContextStatic();
			_numExecutors = Math.max(jsc.sc().getExecutorMemoryStatus().size() - 1, 1);  
			_defaultPar = jsc.defaultParallelism();
			_confOnly = false; //implies env info refresh w/ spark context 
		}
		
		//note: required time for infrastructure analysis on 5 node cluster: ~5-20ms. 
	}

	/**
	 * 
	 */
	public void checkAndRaiseValidationWarningJDKVersion()
	{
		//check for jdk version less than jdk8
		boolean isLtJDK8 = InfrastructureAnalyzer.isJavaVersionLessThanJDK8();

		//check multi-threaded executors
		int numExecutors = getNumExecutors();
		int numCores = getDefaultParallelism();
		boolean multiThreaded = (numCores > numExecutors);
		
		//check for jdk version less than 8 (and raise warning if multi-threaded)
		if( isLtJDK8 && multiThreaded) 
		{
			//get the jre version 
			String version = System.getProperty("java.version");
			
			LOG.warn("########################################################################################");
			LOG.warn("### WARNING: Multi-threaded text reblock may lead to thread contention on JRE < 1.8 ####");
			LOG.warn("### java.version = " + version);
			LOG.warn("### total number of executors = " + numExecutors);
			LOG.warn("### total number of cores = " + numCores);
			LOG.warn("### JDK-7032154: Performance tuning of sun.misc.FloatingDecimal/FormattedFloatingDecimal");
			LOG.warn("### Workaround: Convert text to binary w/ changed configuration of one executor per core");
			LOG.warn("########################################################################################");
		}
	}
	
	///////////////////////////////////////////
	// Cleanup of RDDs and Broadcast variables
	///////
	
	/**
	 * Adds a child rdd object to the lineage of a parent rdd.
	 * 
	 * @param varParent
	 * @param varChild
	 * @throws DMLRuntimeException
	 */
	public void addLineageRDD(String varParent, String varChild) 
		throws DMLRuntimeException 
	{
		RDDObject parent = getCacheableData(varParent).getRDDHandle();
		RDDObject child = getCacheableData(varChild).getRDDHandle();
		
		parent.addLineageChild( child );
	}
	
	/**
	 * Adds a child broadcast object to the lineage of a parent rdd.
	 * 
	 * @param varParent
	 * @param varChild
	 * @throws DMLRuntimeException
	 */
	public void addLineageBroadcast(String varParent, String varChild) 
		throws DMLRuntimeException 
	{
		RDDObject parent = getCacheableData(varParent).getRDDHandle();
		BroadcastObject child = getCacheableData(varChild).getBroadcastHandle();
		
		parent.addLineageChild( child );
	}
	
	/**
	 * 
	 * @param varParent
	 * @param varChild
	 * @param broadcast
	 * @throws DMLRuntimeException
	 */
	public void addLineage(String varParent, String varChild, boolean broadcast) 
		throws DMLRuntimeException
	{
		if( broadcast )
			addLineageBroadcast(varParent, varChild);
		else
			addLineageRDD(varParent, varChild);
	}
	
	@Override
	public void cleanupMatrixObject( MatrixObject mo ) 
		throws DMLRuntimeException
	{
		//NOTE: this method overwrites the default behavior of cleanupMatrixObject
		//and hence is transparently used by rmvar instructions and other users. The
		//core difference is the lineage-based cleanup of RDD and broadcast variables.
		
		try
		{
			if ( mo.isCleanupEnabled() ) 
			{
				//compute ref count only if matrix cleanup actually necessary
				if ( !getVariables().hasReferences(mo) ) 
				{
					//clean cached data	
					mo.clearData(); 
					
					//clean hdfs data if no pending rdd operations on it
					if( mo.isHDFSFileExists() && mo.getFileName()!=null ) {
						if( mo.getRDDHandle()==null ) {
							MapReduceTool.deleteFileWithMTDIfExistOnHDFS(mo.getFileName());
						}
						else { //deferred file removal
							RDDObject rdd = mo.getRDDHandle();
							rdd.setHDFSFilename(mo.getFileName());
						}
					}
					
					//cleanup RDD and broadcast variables (recursive)
					//note: requires that mo.clearData already removed back references
					if( mo.getRDDHandle()!=null ) { 
 						rCleanupLineageObject(mo.getRDDHandle());
					}	
					if( mo.getBroadcastHandle()!=null ) {
						rCleanupLineageObject(mo.getBroadcastHandle());
					}
				}
			}
		}
		catch(Exception ex)
		{
			throw new DMLRuntimeException(ex);
		}
	}
	
	/**
	 * 
	 * @param lob
	 * @throws IOException
	 */
	private void rCleanupLineageObject(LineageObject lob) 
		throws IOException
	{		
		//abort recursive cleanup if still consumers
		if( lob.getNumReferences() > 0 )
			return;
			
		//abort if still reachable through matrix object (via back references for 
		//robustness in function calls and to prevent repeated scans of the symbol table)
		if( lob.hasBackReference() )
			return;
		
		//cleanup current lineage object (from driver/executors)
		//incl deferred hdfs file removal (only if metadata set by cleanup call)
		if( lob instanceof RDDObject ) {
			RDDObject rdd = (RDDObject)lob;
			cleanupRDDVariable(rdd.getRDD());
			if( rdd.getHDFSFilename()!=null ) { //deferred file removal
				MapReduceTool.deleteFileWithMTDIfExistOnHDFS(rdd.getHDFSFilename());
			}
		}
		else if( lob instanceof BroadcastObject ) {
			PartitionedBroadcastMatrix pbm = ((BroadcastObject)lob).getBroadcast();
			if( pbm != null ) //robustness for evictions
				for( Broadcast<PartitionedMatrixBlock> bc : pbm.getBroadcasts() )
					cleanupBroadcastVariable(bc);
		}
	
		//recursively process lineage children
		for( LineageObject c : lob.getLineageChilds() ){
			c.decrementNumReferences();
			rCleanupLineageObject(c);
		}
	}
	
	/**
	 * This call destroys a broadcast variable at all executors and the driver.
	 * Hence, it is intended to be used on rmvar only. Depending on the
	 * ASYNCHRONOUS_VAR_DESTROY configuration, this is asynchronous or not.
	 * 
	 * 
	 * @param inV
	 */
	public void cleanupBroadcastVariable(Broadcast<?> bvar) 
	{
		//in comparison to 'unpersist' (which would only delete the broadcast from the executors),
		//this call also deletes related data from the driver.
		if( bvar.isValid() ) {
			bvar.destroy( ASYNCHRONOUS_VAR_DESTROY );
		}
	}
	
	/**
	 * This call removes an rdd variable from executor memory and disk if required.
	 * Hence, it is intended to be used on rmvar only. Depending on the
	 * ASYNCHRONOUS_VAR_DESTROY configuration, this is asynchronous or not.
	 * 
	 * @param rvar
	 */
	public void cleanupRDDVariable(JavaPairRDD<?,?> rvar) 
	{
		if( rvar.getStorageLevel()!=StorageLevel.NONE() ) {
			rvar.unpersist( ASYNCHRONOUS_VAR_DESTROY );
		}
	}
	
	/**
	 * 
	 * @param var
	 * @throws DMLRuntimeException 
	 */
	@SuppressWarnings("unchecked")
	public void repartitionAndCacheMatrixObject( String var ) 
		throws DMLRuntimeException
	{
		//get input rdd and default storage level
		MatrixObject mo = getMatrixObject(var);
		MatrixCharacteristics mcIn = mo.getMatrixCharacteristics();
		JavaPairRDD<MatrixIndexes,MatrixBlock> in = (JavaPairRDD<MatrixIndexes, MatrixBlock>) 
				getRDDHandleForMatrixObject(mo, InputInfo.BinaryBlockInputInfo);
		
		//avoid unnecessary caching of input in order to reduce memory pressure
		if( mo.getRDDHandle().allowsShortCircuitRead()
			&& isRDDMarkedForCaching(in.id()) && !isRDDCached(in.id()) ) {
			in = (JavaPairRDD<MatrixIndexes,MatrixBlock>)
					((RDDObject)mo.getRDDHandle().getLineageChilds().get(0)).getRDD();
			
			//investigate issue of unnecessarily large number of partitions
			int numPartitions = CheckpointSPInstruction.getNumCoalescePartitions(mcIn, in);
			if( numPartitions < in.partitions().size() )
				in = in.coalesce( numPartitions );
		}
		
		//repartition rdd (force creation of shuffled rdd via merge)
		JavaPairRDD<MatrixIndexes,MatrixBlock> out = RDDAggregateUtils.mergeByKey(in);
		
		//convert mcsr into memory-efficient csr if potentially sparse
		if( OptimizerUtils.checkSparseBlockCSRConversion(mcIn) ) {				
			out = out.mapValues(new CreateSparseBlockFunction(SparseBlock.Type.CSR));
		}
		
		//persist rdd in default storage level 
		out.persist( Checkpoint.DEFAULT_STORAGE_LEVEL )
		   .count(); //trigger caching to prevent contention
		
		//create new rdd handle, in-place of current matrix object
		RDDObject inro =  mo.getRDDHandle();       //guaranteed to exist (see above)
		RDDObject outro = new RDDObject(out, var); //create new rdd object
		outro.setCheckpointRDD(true);              //mark as checkpointed
		outro.addLineageChild(inro);               //keep lineage to prevent cycles on cleanup
		mo.setRDDHandle(outro);				       
	}
	
	/**
	 * 
	 * @param var
	 * @throws DMLRuntimeException
	 */
	@SuppressWarnings("unchecked")
	public void cacheMatrixObject( String var ) 
		throws DMLRuntimeException
	{
		//get input rdd and default storage level
		MatrixObject mo = getMatrixObject(var);
		JavaPairRDD<MatrixIndexes,MatrixBlock> in = (JavaPairRDD<MatrixIndexes, MatrixBlock>) 
				getRDDHandleForMatrixObject(mo, InputInfo.BinaryBlockInputInfo);
		
		//persist rdd (force rdd caching)
		in.count(); //trigger caching to prevent contention			       
	}
	
	/**
	 * 
	 * @param poolName
	 */
	public void setThreadLocalSchedulerPool(String poolName) {
		if( FAIR_SCHEDULER_MODE ) {
			getSparkContext().sc().setLocalProperty(
					"spark.scheduler.pool", poolName);
		}
	}
	
	/**
	 * 
	 */
	public void cleanupThreadLocalSchedulerPool() {
		if( FAIR_SCHEDULER_MODE ) {
			getSparkContext().sc().setLocalProperty(
					"spark.scheduler.pool", null);
		}
	}
	
	/**
	 * 
	 * @param rddID
	 * @return
	 */
	private boolean isRDDMarkedForCaching( int rddID ) {
		JavaSparkContext jsc = getSparkContext();
		return jsc.sc().getPersistentRDDs().contains(rddID);
	}
	
	/**
	 * 
	 * @param rddID
	 * @return
	 */
	private boolean isRDDCached( int rddID ) {
		//check that rdd is marked for caching
		JavaSparkContext jsc = getSparkContext();
		if( !jsc.sc().getPersistentRDDs().contains(rddID) ) {
			return false;
		}
		
		//check that rdd is actually already cached
		for( RDDInfo info : jsc.sc().getRDDStorageInfo() ) {
			if( info.id() == rddID )
				return info.isCached();
		}
		return false;
	}
	
	///////////////////////////////////////////
	// Debug String Handling (see explain); TODO to be removed
	///////

	/**
	 * 
	 * @param inst
	 * @param outputVarName
	 * @throws DMLRuntimeException
	 */
	public void setDebugString(SPInstruction inst, String outputVarName) 
		throws DMLRuntimeException 
	{
		RDDObject parentLineage = getMatrixObject(outputVarName).getRDDHandle();
		
		if( parentLineage == null || parentLineage.getRDD() == null )
			return;
		
		MLContextProxy.addRDDForInstructionForMonitoring(inst, parentLineage.getRDD().id());
		
		JavaPairRDD<?, ?> out = parentLineage.getRDD();
		JavaPairRDD<?, ?> in1 = null; 
		JavaPairRDD<?, ?> in2 = null;
		String input1VarName = null; 
		String input2VarName = null;
		if(parentLineage.getLineageChilds() != null) {
			for(LineageObject child : parentLineage.getLineageChilds()) {
				if(child instanceof RDDObject) {
					if(in1 == null) {
						in1 = ((RDDObject) child).getRDD();
						input1VarName = child.getVarName();
					}
					else if(in2 == null) {
						in2 = ((RDDObject) child).getRDD();
						input2VarName = child.getVarName();
					}
					else {
						throw new DMLRuntimeException("PRINT_EXPLAIN_WITH_LINEAGE not yet supported for three outputs");
					}
				}
			}
		}
		setLineageInfoForExplain(inst, out, in1, input1VarName, in2, input2VarName);
	}
	
	// The most expensive operation here is rdd.toDebugString() which can be a major hit because
	// of unrolling lazy evaluation of Spark. Hence, it is guarded against it along with flag 'PRINT_EXPLAIN_WITH_LINEAGE' which is 
	// enabled only through MLContext. This way, it doesnot affect our performance evaluation through non-MLContext path
	private void setLineageInfoForExplain(SPInstruction inst, 
			JavaPairRDD<?, ?> out, 
			JavaPairRDD<?, ?> in1, String in1Name, 
			JavaPairRDD<?, ?> in2, String in2Name) throws DMLRuntimeException {
		
			
		// RDDInfo outInfo = org.apache.spark.storage.RDDInfo.fromRdd(out.rdd());
		
		// First fetch start lines from input RDDs
		String startLine1 = null; 
		String startLine2 = null;
		int i1length = 0, i2length = 0;
		if(in1 != null) {
			String [] lines = in1.toDebugString().split("\\r?\\n");
			startLine1 = SparkUtils.getStartLineFromSparkDebugInfo(lines[0]); // lines[0].substring(4, lines[0].length());
			i1length = lines.length;
		}
		if(in2 != null) {
			String [] lines = in2.toDebugString().split("\\r?\\n");
			startLine2 =  SparkUtils.getStartLineFromSparkDebugInfo(lines[0]); // lines[0].substring(4, lines[0].length());
			i2length = lines.length;
		}
		
		String outDebugString = "";
		int skip = 0;
		
		// Now process output RDD and replace inputRDD debug string by the matrix variable name
		String [] outLines = out.toDebugString().split("\\r?\\n");
		for(int i = 0; i < outLines.length; i++) {
			if(skip > 0) {
				skip--;
				// outDebugString += "\nSKIP:" + outLines[i];
			}
			else if(startLine1 != null && outLines[i].contains(startLine1)) {
				String prefix = SparkUtils.getPrefixFromSparkDebugInfo(outLines[i]); // outLines[i].substring(0, outLines[i].length() - startLine1.length());
				outDebugString += "\n" + prefix + "[[" + in1Name + "]]";
				//outDebugString += "\n{" + prefix + "}[[" + in1Name + "]] => " + outLines[i];
				skip = i1length - 1;  
			}
			else if(startLine2 != null && outLines[i].contains(startLine2)) {
				String prefix = SparkUtils.getPrefixFromSparkDebugInfo(outLines[i]); // outLines[i].substring(0, outLines[i].length() - startLine2.length());
				outDebugString += "\n" + prefix + "[[" + in2Name + "]]";
				skip = i2length - 1;
			}
			else {
				outDebugString += "\n" + outLines[i];
			}
		}
		
		MLContext mlContext = MLContextProxy.getActiveMLContext();
		if(mlContext != null && mlContext.getMonitoringUtil() != null) {
			mlContext.getMonitoringUtil().setLineageInfo(inst, outDebugString);
		}
		else {
			throw new DMLRuntimeException("The method setLineageInfoForExplain should be called only through MLContext");
		}
		
	}
	
}
