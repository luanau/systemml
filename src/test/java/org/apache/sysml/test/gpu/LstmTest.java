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

package org.apache.sysml.test.gpu;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.apache.sysml.runtime.instructions.gpu.DnnGPUInstruction;
import org.apache.sysml.runtime.instructions.gpu.DnnGPUInstruction.LstmOperator;
import org.apache.sysml.test.utils.TestUtils;
import org.junit.Test;

/**
 * Tests lstm builtin function
 */
public class LstmTest extends GPUTests {

	private final static String TEST_NAME = "LstmTests";
	private final int seed = 42;

	@Override
	public void setUp() {
		super.setUp();
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_DIR, TEST_NAME);
		getAndLoadTestConfiguration(TEST_NAME);
	}

	@Test
	public void testLstmForward1() {
		testLstmCuDNNWithNNBuiltinOperator(1, 1, 1, 1, "TRUE", 0.9);
	}
	
	@Test
	public void testLstmForward2() {
		testLstmCuDNNWithNNBuiltinOperator(1, 1, 1, 1, "FALSE", 0.9);
	}
	
	@Test
	public void testLstmForward3() {
		testLstmCuDNNWithNNBuiltinOperator(20, 13, 50, 10, "TRUE", 0.9);
	}
	
	@Test
	public void testLstmForward4() {
		testLstmCuDNNWithNNBuiltinOperator(20, 13, 50, 10, "FALSE", 0.9);
	}
	
	@Test
	public void testLstmForward5() {
		testLstmCuDNNWithNNBuiltinOperator(1, 3, 5, 1, "TRUE", 0.9);
	}
	
	@Test
	public void testLstmForward6() {
		testLstmCuDNNWithNNBuiltinOperator(1, 3, 5, 1, "FALSE", 0.9);
	}
	
	@Test
	public void testLstmForward7() {
		testLstmCuDNNWithNNBuiltinOperator(20, 13, 50, 10, "TRUE", 0.1);
	}
	
	@Test
	public void testLstmForward8() {
		testLstmCuDNNWithNNBuiltinOperator(20, 13, 50, 10, "FALSE", 0.1);
	}
	
	@Test
	public void testLstmForward9() {
		testLstmCuDNNWithNNLayer(1, 1, 1, 1, "TRUE", 0.9);
	}
	
	@Test
	public void testLstmForward10() {
		testLstmCuDNNWithNNLayer(1, 1, 1, 1, "FALSE", 0.9);
	}
	
	@Test
	public void testLstmForward11() {
		testLstmCuDNNWithNNLayer(20, 13, 50, 10, "TRUE", 0.9);
	}
	
	@Test
	public void testLstmForward12() {
		testLstmCuDNNWithNNLayer(20, 13, 50, 10, "FALSE", 0.9);
	}
	
	
	public void testLstmCuDNNWithNNBuiltinOperator(int N, int T, int D, int M, String returnSequences, double sparsity) {
		String scriptStr = "source(\"nn/layers/lstm_staging.dml\") as lstm;\n "
				+ "[output, c] = lstm::forward(x, w, b, " + returnSequences + ", out0, c0)";
		
		HashMap<String, Object> inputs = new HashMap<>();
		inputs.put("x", generateInputMatrix(spark, N, T*D, 0, 10, sparsity, seed));
		inputs.put("w", generateInputMatrix(spark, D+M, 4*M, 0, 10, sparsity, seed));
		inputs.put("b", generateInputMatrix(spark, 1, 4*M, 0, 10, sparsity, seed));
		inputs.put("out0", generateInputMatrix(spark, N, M, 0, 10, sparsity, seed));
		inputs.put("c0", generateInputMatrix(spark, N, M, 0, 10, sparsity, seed));
		List<String> outputs = Arrays.asList("output", "c");
		List<Object> outGPUWithCuDNN = null;
		List<Object> outGPUWithNN = null;
		synchronized (DnnGPUInstruction.FORCED_LSTM_OP) {
			try {
				DnnGPUInstruction.FORCED_LSTM_OP = LstmOperator.CUDNN;
				outGPUWithCuDNN = runOnGPU(spark, scriptStr, inputs, outputs);
				inputs = new HashMap<>();
				inputs.put("x", generateInputMatrix(spark, N, T*D, 0, 10, sparsity, seed));
				inputs.put("w", generateInputMatrix(spark, D+M, 4*M, 0, 10, sparsity, seed));
				inputs.put("b", generateInputMatrix(spark, 1, 4*M, 0, 10, sparsity, seed));
				inputs.put("out0", generateInputMatrix(spark, N, M, 0, 10, sparsity, seed));
				inputs.put("c0", generateInputMatrix(spark, N, M, 0, 10, sparsity, seed));
				DnnGPUInstruction.FORCED_LSTM_OP = LstmOperator.DENSE_NN;
				outGPUWithNN = runOnGPU(spark, scriptStr, inputs, outputs);
			}
			finally {
				DnnGPUInstruction.FORCED_LSTM_OP = LstmOperator.NONE;
			}
		}
		assertEqualObjects(outGPUWithCuDNN.get(0), outGPUWithNN.get(0));
		assertEqualObjects(outGPUWithCuDNN.get(1), outGPUWithNN.get(1));
	}
	
	public void testLstmCuDNNWithNNLayer(int N, int T, int D, int M, String returnSequences, double sparsity) {
		String scriptStr1 = "source(\"nn/layers/lstm_staging.dml\") as lstm;\n "
				+ "[output, c] = lstm::forward(x, w, b, " + returnSequences + ", out0, c0)";
		String scriptStr2 = "source(\"nn/layers/lstm.dml\") as lstm;\n "
				+ "[output, c, cache_out, cache_c, cache_ifog] = lstm::forward(x, w, b, " 
				+ T + ", " + D + ", " + returnSequences + ", out0, c0)";
		
		HashMap<String, Object> inputs = new HashMap<>();
		inputs.put("x", generateInputMatrix(spark, N, T*D, 0, 10, sparsity, seed));
		inputs.put("w", generateInputMatrix(spark, D+M, 4*M, 0, 10, sparsity, seed));
		inputs.put("b", generateInputMatrix(spark, 1, 4*M, 0, 10, sparsity, seed));
		inputs.put("out0", generateInputMatrix(spark, N, M, 0, 10, sparsity, seed));
		inputs.put("c0", generateInputMatrix(spark, N, M, 0, 10, sparsity, seed));
		List<String> outputs = Arrays.asList("output", "c");
		List<Object> outGPUWithCuDNN = null;
		List<Object> outCPUWithNN = null;
		synchronized (DnnGPUInstruction.FORCED_LSTM_OP) {
			try {
				DnnGPUInstruction.FORCED_LSTM_OP = LstmOperator.CUDNN;
				outGPUWithCuDNN = runOnGPU(spark, scriptStr1, inputs, outputs);
				outCPUWithNN = runOnCPU(spark, scriptStr2, inputs, outputs);
			}
			finally {
				DnnGPUInstruction.FORCED_LSTM_OP = LstmOperator.NONE;
			}
		}
		assertEqualObjects(outGPUWithCuDNN.get(0), outCPUWithNN.get(0));
		assertEqualObjects(outGPUWithCuDNN.get(1), outCPUWithNN.get(1));
	}
}
