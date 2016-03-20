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

package org.apache.sysml.runtime.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;

import org.apache.sysml.api.DMLScript;
import org.apache.sysml.conf.ConfigurationManager;
import org.apache.sysml.conf.DMLConfig;
import org.apache.sysml.lops.Lop;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.parfor.stat.InfrastructureAnalyzer;
import org.apache.sysml.runtime.controlprogram.parfor.util.IDSequence;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.MatrixIndexes;
import org.apache.sysml.runtime.matrix.data.MatrixValue;
import org.apache.sysml.runtime.matrix.data.Pair;

public class LocalFileUtils 
{
	public static final int BUFFER_SIZE = 8192;
	
	//unique IDs per JVM for tmp files
	private static IDSequence _seq = null;
	private static String _workingDir = null;
	
	//categories of temp files under process-specific working dir
	public static final String CATEGORY_CACHE        = "cache";
	public static final String CATEGORY_PARTITIONING = "partitioning";
	public static final String CATEGORY_RESULTMERGE  = "resultmerge";
	public static final String CATEGORY_WORK         = "work";
	
	static
	{
		_seq = new IDSequence();
	}
	
	/**
	 * 
	 * @param filePathAndName
	 * @return
	 * @throws IOException
	 */
	public static MatrixBlock readMatrixBlockFromLocal(String filePathAndName)
		throws IOException
	{
		return readMatrixBlockFromLocal(filePathAndName, new MatrixBlock());
	}
	
	/**
	 * 
	 * @param filePathAndName
	 * @return
	 * @throws IOException
	 */
	public static MatrixBlock readMatrixBlockFromLocal(String filePathAndName, MatrixBlock ret)
		throws IOException
	{
		FileInputStream fis = new FileInputStream( filePathAndName );
		//BufferedInputStream bis = new BufferedInputStream( fis, BUFFER_SIZE );
		//DataInputStream in = new DataInputStream( bis );
		FastBufferedDataInputStream in = new FastBufferedDataInputStream(fis, BUFFER_SIZE);
		
		try
		{
			ret.readFields(in);
		}
		finally
		{
			if( in != null )
				in.close();
		}
			
		return ret;
	}
	
	/**
	 * 
	 * @param filePathAndName
	 * @param mb
	 * @throws IOException
	 */
	public static void writeMatrixBlockToLocal (String filePathAndName, MatrixBlock mb)
		throws IOException
	{	
		FileOutputStream fos = new FileOutputStream( filePathAndName );
		//BufferedOutputStream bos = new BufferedOutputStream( fos, BUFFER_SIZE );
		//DataOutputStream out = new DataOutputStream( bos );
		FastBufferedDataOutputStream out = new FastBufferedDataOutputStream(fos, BUFFER_SIZE);
		
		try 
		{
			mb.write(out);
		}
		finally
		{
			if( out != null )
				out.close();	
		}	
	}
	
	
	/**
	 * 
	 * @param filePathAndName
	 * @param data
	 * @throws IOException
	 */
	public static void writeByteArrayToLocal( String filePathAndName, byte[] data )
		throws IOException
	{		
		FileOutputStream fos = new FileOutputStream( filePathAndName );
		
		try 
		{
			fos.write( data );
		}
		finally
		{
			if( fos != null )
				fos.close ();	
		}	
	}
	
	/**
	 * 
	 * @param filePathAndName
	 * @param data
	 * @throws IOException
	 */
	public static void writeByteArrayToLocal( String filePathAndName, byte[][] data )
		throws IOException
	{		
		FileOutputStream fos = new FileOutputStream( filePathAndName );
		
		try 
		{
			for( int i=0; i<data.length; i++ )
				if( data[i]!=null )
					fos.write( data[i] );
		}
		finally
		{
			if( fos != null )
				fos.close ();	
		}	
	}

	/**
	 * 
	 * @param filePathAndName
	 * @param outValues
	 * @return
	 * @throws IOException 
	 */
	public static int readBlockSequenceFromLocal( String filePathAndName, Pair<MatrixIndexes,MatrixValue>[] outValues, HashMap<MatrixIndexes, Integer> outMap) 
		throws IOException
	{
		FileInputStream fis = new FileInputStream( filePathAndName );
		FastBufferedDataInputStream in = new FastBufferedDataInputStream( fis, BUFFER_SIZE );
		int bufferSize = 0;
		
		try
		{
			int len = in.readInt();
			for( int i=0; i<len; i++ )
			{
				outValues[i].getKey().readFields(in);
				outValues[i].getValue().readFields(in);
				if( outMap!=null )
					outMap.put( outValues[i].getKey(), i );
			}
			bufferSize = len;
		}
		finally
		{
			if( in != null )
				in.close();
		}
			
		return bufferSize;
	}
	
	/**
	 * 
	 * @param filePathAndName
	 * @param inValues
	 * @param len
	 * @throws IOException 
	 */
	public static void writeBlockSequenceToLocal( String filePathAndName, Pair<MatrixIndexes,MatrixValue>[] inValues, int len ) 
		throws IOException
	{
		if( len > inValues.length )
			throw new IOException("Invalid length of block sequence: len="+len+" vs data="+inValues.length);
		
		FileOutputStream fos = new FileOutputStream( filePathAndName );
		FastBufferedDataOutputStream out = new FastBufferedDataOutputStream(fos, BUFFER_SIZE);
		
		try 
		{
			out.writeInt(len);
			for( int i=0; i<len; i++ )
			{
				inValues[i].getKey().write(out);
				inValues[i].getValue().write(out);
			}
		}
		finally
		{
			if( out != null )
				out.close ();	
		}	
	}


	/**
	 * 
	 * @param dir
	 * @return
	 */
	public static boolean createLocalFileIfNotExist(String dir) 
	{
		boolean ret = true;
		
		File fdir = new File(dir);
		if( !fdir.exists() ) {
			ret = fdir.mkdirs();
		}
		
		return ret;
	}
	
	/**
	 * 
	 * @param dir
	 */
	public static void deleteFileIfExists(String dir) 
	{
		deleteFileIfExists(dir, false);
	}
	
	/**
	 * 
	 * @param dir
	 * @param fileOnly
	 */
	public static void deleteFileIfExists(String dir, boolean fileOnly) 
	{
		File fdir = new File(dir);
		
		if( fdir.exists() ) 
		{
			if( fileOnly ) //delete single file
				fdir.delete();
			else //recursively delete entire directory
				rDelete(fdir);	
		}
	}
	
	/**
	 * 
	 * @param dir
	 * @return
	 */
	public static boolean isExisting(String dir) 
	{
		File fdir = new File(dir);
		return fdir.exists();
	}

	
	/**
	 * 
	 * @param dir
	 * @param permission
	 * @return
	 */
	public static boolean createLocalFileIfNotExist( String dir, String permission )
	{
		boolean ret = true;
		
		File fdir = new File(dir);
		if( !fdir.exists() ) {
			ret = fdir.mkdirs();
			setLocalFilePermissions(fdir, DMLConfig.DEFAULT_SHARED_DIR_PERMISSION);
		}
		
		return ret;
	}
	
	/**
	 * 
	 * @param file
	 * @param permissions
	 */
	public static void setLocalFilePermissions( File file, String permissions )
	{
		//note: user and group treated the same way
		char[] c = permissions.toCharArray();
		short sU = (short)(c[0]-48);
		short sO = (short)(c[2]-48); 
		
		file.setExecutable( (sU&1)==1, (sO&1)==0 );
		file.setWritable(   (sU&2)==2, (sO&2)==0 );
		file.setReadable(   (sU&4)==4, (sO&4)==0 );
	}

	
	///////////
	// working dir handling
	///
	
	/**
	 * 
	 * @param dir
	 * @return
	 */
	public static String checkAndCreateStagingDir(String dir) 
	{
		File f =  new File(dir);		
		if( !f.exists() )
			f.mkdirs();
		
		return dir;
	}

	/**
	 * 
	 * @return
	 * @throws DMLRuntimeException 
	 */
	public static String createWorkingDirectory() 
		throws DMLRuntimeException 
	{
		return createWorkingDirectoryWithUUID( DMLScript.getUUID() );
	}
	
	/**
	 * 
	 * @return
	 * @throws IOException 
	 */
	public static String createWorkingDirectoryWithUUID( String uuid )
		throws DMLRuntimeException 
	{
		//create local tmp dir if not existing
		String dirRoot = null;
		DMLConfig conf = ConfigurationManager.getDMLConfig();
		if( conf != null ) 
			dirRoot = conf.getTextValue(DMLConfig.LOCAL_TMP_DIR);
		else 
			dirRoot = DMLConfig.getDefaultTextValue(DMLConfig.LOCAL_TMP_DIR);		
		
		//create shared staging dir if not existing
		if( !LocalFileUtils.createLocalFileIfNotExist(dirRoot, DMLConfig.DEFAULT_SHARED_DIR_PERMISSION) ){
			throw new DMLRuntimeException("Failed to create non-existing local working directory: "+dirRoot);
		}
		
		//create process specific sub tmp dir
		StringBuilder sb = new StringBuilder();
		sb.append( dirRoot );
		sb.append(Lop.FILE_SEPARATOR);
		sb.append(Lop.PROCESS_PREFIX);
		sb.append( uuid );
		sb.append(Lop.FILE_SEPARATOR);
		_workingDir = sb.toString();
		
		//create process-specific staging dir if not existing
		if( !LocalFileUtils.createLocalFileIfNotExist(_workingDir) ){
			throw new DMLRuntimeException("Failed to create local working directory: "+_workingDir);
		}
		
		return _workingDir;
	}
	
	/**
	 * 
	 */
	public static void cleanupWorkingDirectory() 
	{
		if( _workingDir != null )
			cleanupWorkingDirectory( _workingDir );
	}
		
	/**
	 * 
	 * @param dir
	 * @return
	 */
	public static void cleanupWorkingDirectory(String dir) 
	{
		File f =  new File(dir);
		if( f.exists() )
			rDelete(f);
	}
	
	public static int cleanupRcWorkingDirectory(String dir) 
	{
		int ret = 0;
		
		File f =  new File(dir);
		if( f.exists() )
			ret += rcDelete(f);
		
		return ret;
	}
	
	/**
	 * Recursively deletes an entire local file system directory.
	 * 
	 * @param dir
	 */
	public static void rDelete(File dir)
	{
		//recursively delete files if required
		if( dir.isDirectory() )
		{
			File[] files = dir.listFiles();
			for( File f : files )
				rDelete( f );	
		}
		
		//delete file/dir itself
		dir.delete();
	}
	
	/**
	 * Recursively deletes an entire local file system directory
	 * and returns the number of files deleted.
	 * 
	 * @param dir
	 * @return
	 */
	public static int rcDelete(File dir)
	{
		int count = 0;
		
		//recursively delete files if required
		if( dir.isDirectory() )
		{
			File[] files = dir.listFiles();
			for( File f : files )
				count += rcDelete( f );	
		}
		
		//delete file/dir itself
		count += dir.delete() ? 1 : 0;
		
		return count;
	}

	/**
	 * 
	 * @return
	 * @throws DMLRuntimeException 
	 */
	public static String getWorkingDir() 
		throws DMLRuntimeException
	{
		if( _workingDir == null )
			createWorkingDirectory();
		return _workingDir;
	}
	
	/**
	 * 
	 * @param category
	 * @return
	 * @throws DMLRuntimeException 
	 */
	public static String getWorkingDir( String category ) 
		throws DMLRuntimeException
	{
		if( _workingDir == null )
			createWorkingDirectory();
		
		StringBuilder sb = new StringBuilder();
		sb.append( _workingDir );
		sb.append( Lop.FILE_SEPARATOR );
		sb.append( category );
		sb.append( Lop.FILE_SEPARATOR );
		
		return sb.toString();
	}
	
	/**
	 * 
	 * @param category
	 * @return
	 * @throws DMLRuntimeException 
	 */
	public static String getUniqueWorkingDir( String category ) 
		throws DMLRuntimeException
	{
		if( _workingDir == null )
			createWorkingDirectory();
		
		StringBuilder sb = new StringBuilder();
		sb.append( _workingDir );
		sb.append( Lop.FILE_SEPARATOR );
		sb.append( category );
		sb.append( Lop.FILE_SEPARATOR );
		sb.append( "tmp" );
		sb.append( _seq.getNextID() );
		
		return sb.toString();
	}
	
	/**
	 * Validate external directory and filenames as soon as they enter the system
	 * in order to prevent security issues such as path traversal, etc.
	 * Currently, external (user provided) filenames are: scriptfile, config file,
	 * local tmp working dir, hdfs working dir (scratch), read/write expressions,
	 * and several export functionalities. 	 
	 *  
	 * 
	 * @param fname
	 * @param hdfs
	 * @return
	 */
	public static boolean validateExternalFilename( String fname, boolean hdfs )
	{
		boolean ret = true;
		
		//check read local file from hdfs context
		//(note: currently rejected with "wrong fs" anyway but this is impl-specific)
		if( hdfs && !InfrastructureAnalyzer.isLocalMode() 
			&& fname.startsWith("file:") )
		{
			//prevent redirection to local file system
			ret = false; 
		}
		
		//TODO white and black lists according to BI requirements
		
		return ret;
	}
}
