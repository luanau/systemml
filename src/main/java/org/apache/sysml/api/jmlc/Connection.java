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

package org.apache.sysml.api.jmlc;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.sysml.api.DMLException;
import org.apache.sysml.api.DMLScript;
import org.apache.sysml.api.DMLScript.RUNTIME_PLATFORM;
import org.apache.sysml.conf.CompilerConfig;
import org.apache.sysml.conf.CompilerConfig.ConfigType;
import org.apache.sysml.conf.ConfigurationManager;
import org.apache.sysml.conf.DMLConfig;
import org.apache.sysml.hops.rewrite.ProgramRewriter;
import org.apache.sysml.hops.rewrite.RewriteRemovePersistentReadWrite;
import org.apache.sysml.lops.Lop;
import org.apache.sysml.parser.AParserWrapper;
import org.apache.sysml.parser.DMLProgram;
import org.apache.sysml.parser.DMLTranslator;
import org.apache.sysml.parser.DataExpression;
import org.apache.sysml.parser.Expression.ValueType;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.Program;
import org.apache.sysml.runtime.controlprogram.caching.CacheableData;
import org.apache.sysml.runtime.io.IOUtilFunctions;
import org.apache.sysml.runtime.io.MatrixReader;
import org.apache.sysml.runtime.io.MatrixReaderFactory;
import org.apache.sysml.runtime.io.ReaderTextCell;
import org.apache.sysml.runtime.matrix.data.FrameBlock;
import org.apache.sysml.runtime.matrix.data.InputInfo;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.Pair;
import org.apache.sysml.runtime.transform.TfUtils;
import org.apache.sysml.runtime.transform.decode.DecoderRecode;
import org.apache.sysml.runtime.util.DataConverter;
import org.apache.sysml.runtime.util.MapReduceTool;
import org.apache.sysml.runtime.util.UtilFunctions;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONObject;

/**
 * Interaction with SystemML using the JMLC (Java Machine Learning Connector) API is initiated with
 * a {@link Connection} object. The JMLC API is patterned
 * after JDBC. A DML script is precompiled by calling
 * the {@link #prepareScript(String, String[], String[], boolean)}
 * method or the {@link #prepareScript(String, Map, String[], String[], boolean)}
 * method on the {@link Connection} object, which returns a
 * {@link PreparedScript} object. Note that this is similar to calling
 * a {@code prepareStatement} method on a JDBC {@code Connection} object.
 * 
 * <p>
 * Following this, input variable data is passed to the script by calling the
 * {@code setFrame}, {@code setMatrix}, and {@code setScalar} methods of the {@link PreparedScript}
 * object. The script is executed via {@link PreparedScript}'s
 * {@link PreparedScript#executeScript() executeScript} method,
 * which returns a {@link ResultVariables} object, which is similar to a JDBC
 * {@code ResultSet}. Data can be read from a {@link ResultVariables} object by calling
 * its {@link ResultVariables#getFrame(String) getFrame} and
 * {@link ResultVariables#getMatrix(String) getMatrix} methods.
 * 
 * <p>
 * For examples, please see the following:
 * <ul>
 *   <li>JMLC JUnit test cases (org.apache.sysml.test.integration.functions.jmlc)</li>
 *   <li><a target="_blank" href="http://apache.github.io/incubator-systemml/jmlc.html">JMLC section
 *   of SystemML online documentation</li>
 * </ul>
 */
public class Connection 
{	
	private static final Log LOG = LogFactory.getLog(Connection.class.getName());
	
	private DMLConfig _dmlconf = null;

	/**
	 * Connection constructor, the starting point for any other JMLC API calls.
	 * 
	 */
	public Connection()
	{
		DMLScript.rtplatform = RUNTIME_PLATFORM.SINGLE_NODE;
		
		//setup basic parameters for embedded execution
		//(parser, compiler, and runtime parameters)
		CompilerConfig cconf = new CompilerConfig();
		cconf.set(ConfigType.IGNORE_UNSPECIFIED_ARGS, true);
		cconf.set(ConfigType.IGNORE_READ_WRITE_METADATA, true);
		cconf.set(ConfigType.REJECT_READ_WRITE_UNKNOWNS, false);
		cconf.set(ConfigType.PARALLEL_CP_READ_TEXTFORMATS, false);
		cconf.set(ConfigType.PARALLEL_CP_WRITE_TEXTFORMATS, false);
		cconf.set(ConfigType.PARALLEL_CP_READ_BINARYFORMATS, false);
		cconf.set(ConfigType.PARALLEL_CP_WRITE_BINARYFORMATS, false);
		cconf.set(ConfigType.PARALLEL_CP_MATRIX_OPERATIONS, false);
		cconf.set(ConfigType.PARALLEL_LOCAL_OR_REMOTE_PARFOR, false);
		cconf.set(ConfigType.ALLOW_DYN_RECOMPILATION, false);
		cconf.set(ConfigType.ALLOW_INDIVIDUAL_SB_SPECIFIC_OPS, false);
		cconf.set(ConfigType.ALLOW_CSE_PERSISTENT_READS, false);
		ConfigurationManager.setLocalConfig(cconf);
		
		//disable caching globally 
		CacheableData.disableCaching();
		
		//create thread-local default configuration
		_dmlconf = new DMLConfig();
		ConfigurationManager.setLocalConfig(_dmlconf);
	}
	
	/**
	 * Prepares (precompiles) a script and registers input and output variables.
	 * 
	 * @param script string representing the DML or PyDML script
	 * @param inputs string array of input variables to register
	 * @param outputs string array of output variables to register
	 * @param parsePyDML {@code true} if PyDML, {@code false} if DML
	 * @return PreparedScript object representing the precompiled script
	 * @throws DMLException
	 */
	public PreparedScript prepareScript( String script, String[] inputs, String[] outputs, boolean parsePyDML) 
		throws DMLException 
	{
		return prepareScript(script, new HashMap<String,String>(), inputs, outputs, parsePyDML);
	}
	
	/**
	 * Prepares (precompiles) a script, sets input parameter values, and registers input and output variables.
	 * 
	 * @param script string representing the DML or PyDML script
	 * @param args map of input parameters ($) and their values
	 * @param inputs string array of input variables to register
	 * @param outputs string array of output variables to register
	 * @param parsePyDML {@code true} if PyDML, {@code false} if DML
	 * @return PreparedScript object representing the precompiled script
	 * @throws DMLException
	 */
	public PreparedScript prepareScript( String script, Map<String, String> args, String[] inputs, String[] outputs, boolean parsePyDML) 
		throws DMLException 
	{
		//prepare arguments
		
		//simplified compilation chain
		Program rtprog = null;
		try
		{
			//parsing
			AParserWrapper parser = AParserWrapper.createParser(parsePyDML);
			DMLProgram prog = parser.parse(null, script, args);
			
			//language validate
			DMLTranslator dmlt = new DMLTranslator(prog);
			dmlt.liveVariableAnalysis(prog);			
			dmlt.validateParseTree(prog);
			
			//hop construct/rewrite
			dmlt.constructHops(prog);
			dmlt.rewriteHopsDAG(prog);
			
			//rewrite persistent reads/writes
			RewriteRemovePersistentReadWrite rewrite = new RewriteRemovePersistentReadWrite(inputs, outputs);
			ProgramRewriter rewriter2 = new ProgramRewriter(rewrite);
			rewriter2.rewriteProgramHopDAGs(prog);
			
			//lop construct and runtime prog generation
			dmlt.constructLops(prog);
			rtprog = prog.getRuntimeProgram(_dmlconf);
			
			//final cleanup runtime prog
			JMLCUtils.cleanupRuntimeProgram(rtprog, outputs);
			
			//System.out.println(Explain.explain(rtprog));
		}
		catch(Exception ex)
		{
			throw new DMLException(ex);
		}
			
		//return newly create precompiled script 
		return new PreparedScript(rtprog, inputs, outputs);
	}
	
	/**
	 * Close connection to SystemML, which clears the
	 * thread-local DML and compiler configurations.
	 */
	public void close() {
		//clear thread-local dml / compiler configs
		ConfigurationManager.clearLocalConfigs();
	}
	
	/**
	 * Read a DML or PyDML file as a string.
	 * 
	 * @param fname the filename of the script
	 * @return string content of the script file
	 * @throws IOException
	 */
	public String readScript(String fname) 
		throws IOException
	{
		StringBuilder sb = new StringBuilder();
		BufferedReader in = null;
		try 
		{
			//read from hdfs or gpfs file system
			if(    fname.startsWith("hdfs:") 
				|| fname.startsWith("gpfs:") ) 
			{ 
				FileSystem fs = FileSystem.get(ConfigurationManager.getCachedJobConf());
				Path scriptPath = new Path(fname);
				in = new BufferedReader(new InputStreamReader(fs.open(scriptPath)));
			}
			// from local file system
			else { 
				in = new BufferedReader(new FileReader(fname));
			}
			
			//core script reading
			String tmp = null;
			while ((tmp = in.readLine()) != null) {
				sb.append( tmp );
				sb.append( "\n" );
			}
		} finally {
			IOUtilFunctions.closeSilently(in);
		}
		
		return sb.toString();
	}
	
	/**
	 * Reads an input matrix in arbitrary format from HDFS into a dense double array.
	 * NOTE: this call currently only supports default configurations for CSV.
	 * 
	 * @param fname the filename of the input matrix
	 * @return matrix as a two-dimensional double array
	 * @throws IOException
	 */
	public double[][] readDoubleMatrix(String fname) 
		throws IOException
	{
		try {
			//read json meta data 
			String fnamemtd = DataExpression.getMTDFileName(fname);
			JSONObject jmtd = new DataExpression().readMetadataFile(fnamemtd, false);
			
			//parse json meta data 
			long rows = jmtd.getLong(DataExpression.READROWPARAM);
			long cols = jmtd.getLong(DataExpression.READCOLPARAM);
			int brlen = jmtd.containsKey(DataExpression.ROWBLOCKCOUNTPARAM)?
					jmtd.getInt(DataExpression.ROWBLOCKCOUNTPARAM) : -1;
			int bclen = jmtd.containsKey(DataExpression.COLUMNBLOCKCOUNTPARAM)?
					jmtd.getInt(DataExpression.COLUMNBLOCKCOUNTPARAM) : -1;
			long nnz = jmtd.containsKey(DataExpression.READNUMNONZEROPARAM)?
					jmtd.getLong(DataExpression.READNUMNONZEROPARAM) : -1;
			String format = jmtd.getString(DataExpression.FORMAT_TYPE);
			InputInfo iinfo = InputInfo.stringExternalToInputInfo(format);			
		
			//read matrix file
			return readDoubleMatrix(fname, iinfo, rows, cols, brlen, bclen, nnz);
		}
		catch(Exception ex) {
			throw new IOException(ex);
		}
	}
	
	/**
	 * Reads an input matrix in arbitrary format from HDFS into a dense double array.
	 * NOTE: this call currently only supports default configurations for CSV.
	 * 
	 * @param fname the filename of the input matrix
	 * @param iinfo InputInfo object
	 * @param rows number of rows in the matrix
	 * @param cols number of columns in the matrix
	 * @param brlen number of rows per block
	 * @param bclen number of columns per block
	 * @param nnz number of non-zero values, -1 indicates unknown
	 * @return matrix as a two-dimensional double array
	 * @throws IOException
	 */
	public double[][] readDoubleMatrix(String fname, InputInfo iinfo, long rows, long cols, int brlen, int bclen, long nnz) 
		throws IOException
	{
		try {
			MatrixReader reader = MatrixReaderFactory.createMatrixReader(iinfo);
			MatrixBlock mb = reader.readMatrixFromHDFS(fname, rows, cols, brlen, bclen, nnz);
			return DataConverter.convertToDoubleMatrix(mb);
		}
		catch(Exception ex) {
			throw new IOException(ex);
		}
	}
	
	/**
	 * Converts an input string representation of a matrix in textcell format
	 * into a dense double array. The meta data string is the SystemML generated
	 * .mtd file including the number of rows and columns.
	 * 
	 * @param input string matrix in textcell format
	 * @param meta string representing SystemML matrix metadata in JSON format
	 * @return matrix as a two-dimensional double array
	 * @throws IOException
	 */
	public double[][] convertToDoubleMatrix(String input, String meta) 
		throws IOException
	{
		try {
			//parse json meta data 
			JSONObject jmtd = new JSONObject(meta);
			int rows = jmtd.getInt(DataExpression.READROWPARAM);
			int cols = jmtd.getInt(DataExpression.READCOLPARAM);
			String format = jmtd.getString(DataExpression.FORMAT_TYPE);
	
			//sanity check input format
			if(!(DataExpression.FORMAT_TYPE_VALUE_TEXT.equals(format)
				||DataExpression.FORMAT_TYPE_VALUE_MATRIXMARKET.equals(format))) {
				throw new IOException("Invalid input format (expected: text or mm): "+format);
			}
			
			//parse the input matrix
			return convertToDoubleMatrix(input, rows, cols);
		}
		catch(Exception ex) {
			throw new IOException(ex);
		}
	}
	
	/**
	 * Converts an input string representation of a matrix in textcell format
	 * into a dense double array. The number of rows and columns need to be 
	 * specified because textcell only represents non-zero values and hence
	 * does not define the dimensions in the general case.
	 * 
	 * @param input string matrix in textcell format
	 * @param rows number of rows in the matrix
	 * @param cols number of columns in the matrix
	 * @return matrix as a two-dimensional double array
	 * @throws IOException
	 */
	public double[][] convertToDoubleMatrix(String input, int rows, int cols) 
		throws IOException
	{
		InputStream is = IOUtilFunctions.toInputStream(input);
		return convertToDoubleMatrix(is, rows, cols);
	}
	
	/**
	 * Converts an input stream of a string matrix in textcell format
	 * into a dense double array. The number of rows and columns need to be 
	 * specified because textcell only represents non-zero values and hence
	 * does not define the dimensions in the general case.
	 * 
	 * @param input InputStream to a string matrix in textcell format
	 * @param rows number of rows in the matrix
	 * @param cols number of columns in the matrix
	 * @return matrix as a two-dimensional double array
	 * @throws IOException
	 */
	public double[][] convertToDoubleMatrix(InputStream input, int rows, int cols) 
		throws IOException
	{
		double[][] ret = null;
		
		try {
			//read input matrix
			ReaderTextCell reader = (ReaderTextCell)MatrixReaderFactory.createMatrixReader(InputInfo.TextCellInputInfo);
			MatrixBlock mb = reader.readMatrixFromInputStream(input, rows, cols, ConfigurationManager.getBlocksize(), ConfigurationManager.getBlocksize(), (long)rows*cols);
		
			//convert to double array
			ret = DataConverter.convertToDoubleMatrix( mb );
		}
		catch(DMLRuntimeException rex) {
			throw new IOException( rex );
		}
		
		return ret;
	}
	
	
	////////////////////////////////////////////
	// Read transform meta data
	////////////////////////////////////////////
	
	/**
	 * Reads transform meta data from an HDFS file path and converts it into an in-memory
	 * FrameBlock object. The column names in the meta data file 'column.names' is processed
	 * with default separator ','.
	 * 
	 * @param metapath  hdfs file path to meta data directory
	 * @return FrameBlock object representing transform metadata
	 * @throws IOException
	 */
	public FrameBlock readTransformMetaDataFromFile(String metapath) throws IOException {
		return readTransformMetaDataFromFile(null, metapath, TfUtils.TXMTD_SEP);
	}
	
	/**
	 * Reads transform meta data from an HDFS file path and converts it into an in-memory
	 * FrameBlock object. The column names in the meta data file 'column.names' is processed
	 * with default separator ','.
	 * 
	 * @param spec      transform specification as json string
	 * @param metapath  hdfs file path to meta data directory
	 * @return FrameBlock object representing transform metadata
	 * @throws IOException
	 */
	public FrameBlock readTransformMetaDataFromFile(String spec, String metapath) throws IOException {
		return readTransformMetaDataFromFile(spec, metapath, TfUtils.TXMTD_SEP);
	}
	
	/**
	 * Reads transform meta data from an HDFS file path and converts it into an in-memory
	 * FrameBlock object.
	 * 
	 * @param spec      transform specification as json string
	 * @param metapath  hdfs file path to meta data directory
	 * @param colDelim  separator for processing column names in the meta data file 'column.names'
	 * @return FrameBlock object representing transform metadata
	 * @throws IOException
	 */
	public FrameBlock readTransformMetaDataFromFile(String spec, String metapath, String colDelim) 
		throws IOException 
	{
		//NOTE: this implementation assumes column alignment of colnames and coltypes
		
		//read column types (for sanity check column names)
		String coltypesStr = MapReduceTool.readStringFromHDFSFile(metapath+File.separator+TfUtils.TXMTD_COLTYPES);
		List<String> coltypes = Arrays.asList(IOUtilFunctions.split(coltypesStr.trim(), TfUtils.TXMTD_SEP));
		
		//read column names
		String colnamesStr = MapReduceTool.readStringFromHDFSFile(metapath+File.separator+TfUtils.TXMTD_COLNAMES);
		List<String> colnames = Arrays.asList(IOUtilFunctions.split(colnamesStr.trim(), colDelim));
		if( coltypes.size() != colnames.size() ) {
			LOG.warn("Number of columns names: "+colnames.size()+" (expected: "+coltypes.size()+").");
			LOG.warn("--Sample column names: "+(!colnames.isEmpty()?colnames.get(0):"null"));
		}
		
		//read meta data (currently only recode supported, without parsing spec)
		Map<String,String> meta = new HashMap<String,String>();
		int rows = 0;
		for( int j=0; j<colnames.size(); j++ ) {
			String colName = colnames.get(j);
			String name = metapath+File.separator+"Recode"+File.separator+colName;
			if( MapReduceTool.existsFileOnHDFS(name+TfUtils.TXMTD_RCD_MAP_SUFFIX) ) {
				meta.put(colName, MapReduceTool.readStringFromHDFSFile(name+TfUtils.TXMTD_RCD_MAP_SUFFIX));
				String ndistinct = MapReduceTool.readStringFromHDFSFile(name+TfUtils.TXMTD_RCD_DISTINCT_SUFFIX);
				rows = Math.max(rows, Integer.parseInt(ndistinct));
			}
			else if( coltypes.get(j).equals("2") ) {
				LOG.warn("Recode map for column '"+colName+"' does not exist.");
			}
		}

		//get list of recode ids
		List<Integer> recodeIDs = parseRecodeColIDs(spec, coltypes);
		
		//create frame block from in-memory strings
		return convertToTransformMetaDataFrame(rows, recodeIDs, colnames, meta);
	}
	
	/**
	 * Reads transform meta data from the class path and converts it into an in-memory
	 * FrameBlock object. The column names in the meta data file 'column.names' is processed
	 * with default separator ','.
	 * 
	 * @param metapath  resource path to meta data directory
	 * @return FrameBlock object representing transform metadata
	 * @throws IOException
	 */
	public FrameBlock readTransformMetaDataFromPath(String metapath) throws IOException {
		return readTransformMetaDataFromPath(null, metapath, TfUtils.TXMTD_SEP);
	}
	
	/**
	 * Reads transform meta data from the class path and converts it into an in-memory
	 * FrameBlock object. The column names in the meta data file 'column.names' is processed
	 * with default separator ','.
	 * 
	 * @param spec      transform specification as json string
	 * @param metapath  resource path to meta data directory
	 * @return FrameBlock object representing transform metadata
	 * @throws IOException
	 */
	public FrameBlock readTransformMetaDataFromPath(String spec, String metapath) throws IOException {
		return readTransformMetaDataFromPath(spec, metapath, TfUtils.TXMTD_SEP);
	}
	
	/**
	 * Reads transform meta data from the class path and converts it into an in-memory
	 * FrameBlock object.
	 * 
	 * @param spec      transform specification as json string
	 * @param metapath  resource path to meta data directory
	 * @param colDelim  separator for processing column names in the meta data file 'column.names'
	 * @return FrameBlock object representing transform metadata
	 * @throws IOException
	 */
	public FrameBlock readTransformMetaDataFromPath(String spec, String metapath, String colDelim) 
		throws IOException 
	{
		//NOTE: this implementation assumes column alignment of colnames and coltypes
		
		//read column types (for sanity check column names)
		String coltypesStr = IOUtilFunctions.toString(Connection.class.getResourceAsStream(metapath+"/"+TfUtils.TXMTD_COLTYPES));
		List<String> coltypes = Arrays.asList(IOUtilFunctions.split(coltypesStr.trim(), TfUtils.TXMTD_SEP));
		
		//read column names
		String colnamesStr = IOUtilFunctions.toString(Connection.class.getResourceAsStream(metapath+"/"+TfUtils.TXMTD_COLNAMES));
		List<String> colnames = Arrays.asList(IOUtilFunctions.split(colnamesStr.trim(), colDelim));
		if( coltypes.size() != colnames.size() ) {
			LOG.warn("Number of columns names: "+colnames.size()+" (expected: "+coltypes.size()+").");
			LOG.warn("--Sample column names: "+(!colnames.isEmpty()?colnames.get(0):"null"));
		}
		
		//read meta data (currently only recode supported, without parsing spec)
		Map<String,String> meta = new HashMap<String,String>();
		int rows = 0;
		for( int j=0; j<colnames.size(); j++ ) {
			String colName = colnames.get(j);
			String name = metapath+"/"+"Recode"+"/"+colName;
			String map = IOUtilFunctions.toString(Connection.class.getResourceAsStream(name+TfUtils.TXMTD_RCD_MAP_SUFFIX));
			if( map != null ) {
				meta.put(colName, map);
				String ndistinct = IOUtilFunctions.toString(Connection.class.getResourceAsStream(name+TfUtils.TXMTD_RCD_DISTINCT_SUFFIX));
				rows = Math.max(rows, Integer.parseInt(ndistinct));
			}
			else if( coltypes.get(j).equals("2") ) {
				LOG.warn("Recode map for column '"+colName+"' does not exist.");
			}
		}
		
		//get list of recode ids
		List<Integer> recodeIDs = parseRecodeColIDs(spec, coltypes);
		
		//create frame block from in-memory strings
		return convertToTransformMetaDataFrame(rows, recodeIDs, colnames, meta);
	}
	
	/**
	 * Converts transform meta data into an in-memory FrameBlock object.
	 * 
	 * @param rows
	 * @param recodeIDs
	 * @param colnames
	 * @param meta
	 * @return
	 * @throws IOException
	 */
	private FrameBlock convertToTransformMetaDataFrame(int rows, List<Integer> recodeIDs, List<String> colnames, Map<String,String> meta) 
		throws IOException 
	{
		//create frame block w/ pure string schema
		List<ValueType> schema = Collections.nCopies(colnames.size(), ValueType.STRING);
		FrameBlock ret = new FrameBlock(schema, colnames);
		ret.ensureAllocatedColumns(rows);
		
		//encode recode maps into frame
		for( Integer colID : recodeIDs ) {
			String name = colnames.get(colID-1);
			String map = meta.get(name);
			if( map == null )
				throw new IOException("Recode map for column '"+name+"' (id="+colID+") not existing.");
			
			InputStream is = new ByteArrayInputStream(map.getBytes("UTF-8"));
			BufferedReader br = new BufferedReader(new InputStreamReader(is));
			Pair<String,String> pair = new Pair<String,String>();
			String line; int rpos = 0;
			while( (line = br.readLine()) != null ) {
				DecoderRecode.parseRecodeMapEntry(line, pair);
				String tmp = pair.getKey() + Lop.DATATYPE_PREFIX + pair.getValue();
				ret.set(rpos++, colID-1, tmp);
			}
		}
		
		return ret;
	}
	
	/**
	 * Parses the given json specification and extracts a list of column ids
	 * that are subject to recoding.
	 * 
	 * @param spec
	 * @param coltypes
	 * @return
	 * @throws IOException
	 */
	private ArrayList<Integer> parseRecodeColIDs(String spec, List<String> coltypes) 
		throws IOException 
	{	
		ArrayList<Integer> specRecodeIDs = new ArrayList<Integer>();
		
		try {
			if( spec != null ) {
				//parse json transform specification for recode col ids
				JSONObject jSpec = new JSONObject(spec);
				if ( jSpec.containsKey(TfUtils.TXMETHOD_RECODE))  {
					JSONArray attrs = null; //TODO simplify once json spec consolidated
					if( jSpec.get(TfUtils.TXMETHOD_RECODE) instanceof JSONObject ) {
						JSONObject obj = (JSONObject) jSpec.get(TfUtils.TXMETHOD_RECODE);
						attrs = (JSONArray) obj.get(TfUtils.JSON_ATTRS);
					}
					else
						attrs = (JSONArray)jSpec.get(TfUtils.TXMETHOD_RECODE);				
					for(int j=0; j<attrs.length(); j++) 
						specRecodeIDs.add(UtilFunctions.toInt(attrs.get(j)));
				}
			}
			else {
				//obtain recode col ids from coltypes 
				for( int j=0; j<coltypes.size(); j++ )
					if( coltypes.get(j).equals("2") )
						specRecodeIDs.add(j+1);
			}
		}
		catch(Exception ex) {
			throw new IOException(ex);
		}
		
		return specRecodeIDs;
	}
}
