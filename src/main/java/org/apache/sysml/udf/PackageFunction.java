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

package org.apache.sysml.udf;

import java.io.Serializable;
import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.sysml.runtime.controlprogram.parfor.util.IDSequence;

/**
 * Abstract class that should be extended to implement a package function.
 * 
 * 
 */
public abstract class PackageFunction implements Serializable 
{	
	protected static final Log LOG = LogFactory.getLog(PackageFunction.class.getName());
	
	private static final long serialVersionUID = 3274150928865462856L;
	
	private ArrayList<FunctionParameter> _function_inputs; // function inputs
	private String _configurationFile; // configuration file parameter that is provided during declaration
	
	private String _baseDir; // base dir for all created files of that external function
	private IDSequence _seq = null;

	public PackageFunction() {
		_function_inputs = new ArrayList<FunctionParameter>();
		_seq = new IDSequence();
	}

	/**
	 * Method to get the number of inputs to this package function.
	 * 
	 * @return
	 */
	public final int getNumFunctionInputs() {
		if (_function_inputs == null)
			throw new RuntimeException("function inputs null");

		return (_function_inputs.size());
	}

	/**
	 * Method to get a specific input to this package function.
	 * 
	 * @param pos
	 * @return
	 */
	public final FunctionParameter getFunctionInput(int pos) {
		if (_function_inputs == null || _function_inputs.size() <= pos)
			throw new RuntimeException("function inputs null or size <= pos");

		return (_function_inputs.get(pos));
	}

	/**
	 * Method to get the number of outputs of this package function. This method
	 * should be implemented in the user's function.
	 * 
	 * @return
	 */
	public abstract int getNumFunctionOutputs();

	/**
	 * Method to get a specific output of this package function. This method
	 * should be implemented in the user's function.
	 * 
	 * @param pos
	 * @return
	 */
	public abstract FunctionParameter getFunctionOutput(int pos);

	/**
	 * Method to set the number of inputs for this package function
	 * 
	 * @param numInputs
	 */
	public final void setNumFunctionInputs(int numInputs) {
		if (_function_inputs == null)
			throw new RuntimeException("function inputs null");

		if (_function_inputs.size() > numInputs)
			throw new RuntimeException("function inputs size > numInputs -- cannot reduce size");

		while (_function_inputs.size() < numInputs)
			_function_inputs.add(null);

	}

	/**
	 * Method to set a specific input for this package function
	 * 
	 * @param input
	 * @param pos
	 */
	public final void setInput(FunctionParameter input, int pos) {
		if (_function_inputs == null || _function_inputs.size() <= pos)
			throw new RuntimeException("function inputs null or size <= pos");

		_function_inputs.set(pos, input);

	}

	/**
	 * Method to set the configuration file for this function.
	 * 
	 * @param fName
	 */
	public final void setConfiguration(String fName) {
		_configurationFile = fName;
	}

	/**
	 * Method to get the configuration file name
	 * 
	 * @return
	 */
	public final String getConfiguration() {
		return _configurationFile;
	}
	
	/**
	 * 
	 * @param dir
	 */
	public void setBaseDir(String dir) {
		_baseDir = dir;
	}

	/**
	 * 
	 * @return
	 */
	public String getBaseDir() {
		return _baseDir;
	}
	
	public String createOutputFilePathAndName( String fname ) {
		return _baseDir + fname + _seq.getNextID();
	}
	

	/**
	 * Method that will be executed to perform this function. 
	 */
	public abstract void execute();
	
}
