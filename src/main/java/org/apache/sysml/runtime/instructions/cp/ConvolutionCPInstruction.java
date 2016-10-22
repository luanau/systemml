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

package org.apache.sysml.runtime.instructions.cp;

import java.util.ArrayList;

import org.apache.sysml.api.DMLScript;
import org.apache.sysml.parser.Expression.DataType;
import org.apache.sysml.parser.Expression.ValueType;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.functionobjects.SwapIndex;
import org.apache.sysml.runtime.instructions.InstructionUtils;
import org.apache.sysml.runtime.matrix.data.LibMatrixDNN;
import org.apache.sysml.runtime.matrix.data.LibMatrixDNN.ConvolutionParameters;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.operators.ReorgOperator;
import org.apache.sysml.runtime.util.ConvolutionUtils;
import org.apache.sysml.utils.Statistics;

public class ConvolutionCPInstruction extends UnaryCPInstruction {
	
	private CPOperand _in2; // used for pooling backward
	private ArrayList<CPOperand> _input_shape;
	private ArrayList<CPOperand> _filter_shape;
	private ArrayList<CPOperand> _stride = new ArrayList<CPOperand>();
	private ArrayList<CPOperand> _padding = new ArrayList<CPOperand>();
	private boolean _reuseNonZeroedOutput = false;
	private int _numThreads = -1;
	public ConvolutionCPInstruction(CPOperand in, CPOperand out, String opcode,
			String istr, ArrayList<CPOperand> stride,
			ArrayList<CPOperand> padding, ArrayList<CPOperand> input_shape,
			ArrayList<CPOperand> filter_shape, int numThreads) {
		super(new ReorgOperator(SwapIndex.getSwapIndexFnObject()), in, out,
				opcode, istr);
		_cptype = CPINSTRUCTION_TYPE.Convolution;
		_stride = stride;
		_padding = padding;
		_input_shape = input_shape;
		_filter_shape = filter_shape;
		_numThreads = numThreads;
	}
	
	public ConvolutionCPInstruction(CPOperand in, CPOperand in2, CPOperand out, String opcode,
			String istr, ArrayList<CPOperand> stride,
			ArrayList<CPOperand> padding, ArrayList<CPOperand> input_shape,
			ArrayList<CPOperand> filter_shape, int numThreads) {
		super(new ReorgOperator(SwapIndex.getSwapIndexFnObject()), in, out,
				opcode, istr);
		_in2 = in2;
		_cptype = CPINSTRUCTION_TYPE.Convolution;
		_stride = stride;
		_padding = padding;
		_input_shape = input_shape;
		_filter_shape = filter_shape;
		_numThreads = numThreads;
	}

	public static ConvolutionCPInstruction parseInstruction(String str)
			throws DMLRuntimeException {
		CPOperand in = new CPOperand("", ValueType.UNKNOWN, DataType.UNKNOWN);
		CPOperand out = new CPOperand("", ValueType.UNKNOWN, DataType.UNKNOWN);

		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		String opcode = parts[0];
		if (opcode.equalsIgnoreCase("maxpooling")) {
			InstructionUtils.checkNumFields(parts, 15);
			// stride1, stride2, padding1, padding2
			// input_shape1, input_shape2, input_shape3, input_shape4,
			// filter_shape1, filter_shape2, filter_shape3, filter_shape4, k
			in.split(parts[1]);
			out.split(parts[14]);

			ArrayList<CPOperand> stride = new ArrayList<CPOperand>();
			ArrayList<CPOperand> padding = new ArrayList<CPOperand>();
			ArrayList<CPOperand> input_shape = new ArrayList<CPOperand>();
			ArrayList<CPOperand> filter_shape = new ArrayList<CPOperand>();
			stride.add(new CPOperand(parts[2]));
			stride.add(new CPOperand(parts[3]));
			padding.add(new CPOperand(parts[4]));
			padding.add(new CPOperand(parts[5]));
			input_shape.add(new CPOperand(parts[6]));
			input_shape.add(new CPOperand(parts[7]));
			input_shape.add(new CPOperand(parts[8]));
			input_shape.add(new CPOperand(parts[9]));
			filter_shape.add(new CPOperand(parts[10]));
			filter_shape.add(new CPOperand(parts[11]));
			filter_shape.add(new CPOperand(parts[12]));
			filter_shape.add(new CPOperand(parts[13]));
			int k = Integer.parseInt(parts[15]);

			return new ConvolutionCPInstruction(in, out, opcode, str, stride,
					padding, input_shape, filter_shape, k);
		} 
		else if (opcode.equalsIgnoreCase("maxpooling_backward")
				|| opcode.equalsIgnoreCase("conv2d")
				|| opcode.equalsIgnoreCase("conv2d_backward_filter")
				|| opcode.equalsIgnoreCase("conv2d_backward_data")) {
			InstructionUtils.checkNumFields(parts, 16);
			// dout, stride1, stride2, padding1, padding2
			// input_shape1, input_shape2, input_shape3, input_shape4,
			// filter_shape1, filter_shape2, filter_shape3, filter_shape4, k
			in.split(parts[1]);
			CPOperand in2 = new CPOperand("", ValueType.UNKNOWN, DataType.UNKNOWN);
			in2.split(parts[2]);
			out.split(parts[15]);

			ArrayList<CPOperand> stride = new ArrayList<CPOperand>();
			ArrayList<CPOperand> padding = new ArrayList<CPOperand>();
			ArrayList<CPOperand> input_shape = new ArrayList<CPOperand>();
			ArrayList<CPOperand> filter_shape = new ArrayList<CPOperand>();
			stride.add(new CPOperand(parts[3]));
			stride.add(new CPOperand(parts[4]));
			padding.add(new CPOperand(parts[5]));
			padding.add(new CPOperand(parts[6]));
			input_shape.add(new CPOperand(parts[7]));
			input_shape.add(new CPOperand(parts[8]));
			input_shape.add(new CPOperand(parts[9]));
			input_shape.add(new CPOperand(parts[10]));
			filter_shape.add(new CPOperand(parts[11]));
			filter_shape.add(new CPOperand(parts[12]));
			filter_shape.add(new CPOperand(parts[13]));
			filter_shape.add(new CPOperand(parts[14]));
			int k = Integer.parseInt(parts[16]);

			return new ConvolutionCPInstruction(in, in2, out, opcode, str, stride,
					padding, input_shape, filter_shape, k);
		} 
		else {
			throw new DMLRuntimeException("Unknown opcode while parsing a ConvolutionCPInstruction: " + str);
		}
	}

	private int getScalarInput(ExecutionContext ec, ArrayList<CPOperand> aL,
			int index) throws DMLRuntimeException {
		return (int) ec.getScalarInput(aL.get(index).getName(),
				aL.get(index).getValueType(), aL.get(index).isLiteral())
				.getLongValue();
	}
	
	@Override
	public void processInstruction(ExecutionContext ec)
			throws DMLRuntimeException {
		// acquire inputs
		MatrixBlock outputBlock = null;
		MatrixBlock matBlock = ec.getMatrixInput(input1.getName());
		int pad_h = getScalarInput(ec, _padding, 0);
		int pad_w = getScalarInput(ec, _padding, 1);
		int stride_h = getScalarInput(ec, _stride, 0);
		int stride_w = getScalarInput(ec, _stride, 1);

		int N = getScalarInput(ec, _input_shape, 0);
		int C = getScalarInput(ec, _input_shape, 1);
		int H = getScalarInput(ec, _input_shape, 2);
		int W = getScalarInput(ec, _input_shape, 3);

		int K = getScalarInput(ec, _filter_shape, 0);
		
		int R = getScalarInput(ec, _filter_shape, 2);
		int S = getScalarInput(ec, _filter_shape, 3);
		int P = (int) ConvolutionUtils.getP(H, R, stride_h, pad_h);
		int Q = (int) ConvolutionUtils.getQ(W, S, stride_w, pad_w);
		
		ConvolutionParameters params = new ConvolutionParameters(N, C, H, W, K, R, S, stride_h, stride_w, pad_h, pad_w, _numThreads);
		if (instOpcode.equalsIgnoreCase("maxpooling")) {
			if(matBlock.isEmptyBlock()) {
				outputBlock = new MatrixBlock(N, C*P*Q, true, 0);
			}
			else {
				// Is eligible for REUSE_NONZEROED_OUTPUT but cannot guarantee that previous output has been rmvar-ed
				// without somewhat expensive HashMap checks
				outputBlock = getDenseOutputBlock(ec, N, C*P*Q, true);
				params.setReuseNonZeroedOutput(_reuseNonZeroedOutput);
				LibMatrixDNN.maxpooling(matBlock, outputBlock, params);
			}
			ec.setMetaData(getOutputVariableName(), N, C*P*Q);
		}
		else if (instOpcode.equalsIgnoreCase("maxpooling_backward")) {
			MatrixBlock dout = ec.getMatrixInput(_in2.getName());
			if(matBlock.isEmptyBlock() || dout.isEmptyBlock()) {
				outputBlock = new MatrixBlock(N, C*H*W, true, 0);
			}
			else {
				// Is eligible for REUSE_NONZEROED_OUTPUT but cannot guarantee that previous output has been rmvar-ed
				// without somewhat expensive HashMap checks
				outputBlock = getDenseOutputBlock(ec, N, C*H*W, false);
				params.setReuseNonZeroedOutput(_reuseNonZeroedOutput);
				LibMatrixDNN.maxpooling_backward(matBlock, dout, outputBlock, params);
			}
			ec.releaseMatrixInput(_in2.getName());
			ec.setMetaData(getOutputVariableName(), N, C*H*W);
		}
		else if (instOpcode.equalsIgnoreCase("conv2d")) {
			MatrixBlock filter = ec.getMatrixInput(_in2.getName());
			if(filter.isEmptyBlock() || matBlock.isEmptyBlock()) {
				outputBlock = new MatrixBlock(N, K*P*Q, true, 0);
			}
			else {
				outputBlock = getDenseOutputBlock(ec, N, K*P*Q, false);
				params.setReuseNonZeroedOutput(_reuseNonZeroedOutput);
				LibMatrixDNN.conv2d(matBlock, filter, outputBlock, params);
			}
			ec.releaseMatrixInput(_in2.getName());
			ec.setMetaData(getOutputVariableName(), N, K*P*Q);
		}
		else if (instOpcode.equalsIgnoreCase("conv2d_backward_filter")) {
			MatrixBlock dout = ec.getMatrixInput(_in2.getName());
			if(dout.isEmptyBlock() || matBlock.isEmptyBlock()) {
				outputBlock = new MatrixBlock(K, C*R*S, true, 0);
			}
			else {
				outputBlock = getDenseOutputBlock(ec, K, C*R*S, false);
				params.setReuseNonZeroedOutput(_reuseNonZeroedOutput);
				LibMatrixDNN.conv2d_backward_filter(matBlock, dout, outputBlock, params);
			}
			ec.releaseMatrixInput(_in2.getName());
			ec.setMetaData(getOutputVariableName(), K, C*R*S);
		}
		else if (instOpcode.equalsIgnoreCase("conv2d_backward_data")) {
			MatrixBlock dout = ec.getMatrixInput(_in2.getName());
			if(dout.isEmptyBlock() || matBlock.isEmptyBlock()) {
				outputBlock = new MatrixBlock(N, C * H * W, true, 0);
			}
			else {
				outputBlock = getDenseOutputBlock(ec, N, C * H * W, false);
				params.setReuseNonZeroedOutput(_reuseNonZeroedOutput);
				LibMatrixDNN.conv2d_backward_data(matBlock, dout, outputBlock, params);
			}
			ec.releaseMatrixInput(_in2.getName());
			ec.setMetaData(getOutputVariableName(), N, C*H*W);
		}
		else {
			throw new DMLRuntimeException("Unsupported op code " + instOpcode);
		}
		
		// release inputs/outputs
		ec.releaseMatrixInput(input1.getName());
		ec.setMatrixOutput(getOutputVariableName(), outputBlock);
	}
	
	@SuppressWarnings("unused")
	private MatrixBlock getDenseOutputBlock(ExecutionContext ec, int numRows, int numCols, boolean reuseNonZeroedOutput1) throws DMLRuntimeException {
		long start = -1;
		if(DMLScript.STATISTICS)
			start = System.nanoTime();
		
		MatrixBlock outputBlock = new MatrixBlock(numRows, numCols, false, numRows * numCols);
		_reuseNonZeroedOutput = false;
		if(reuseNonZeroedOutput1 && DMLScript.REUSE_NONZEROED_OUTPUT) {
			_reuseNonZeroedOutput = true;
			outputBlock.allocateDenseBlock(true, !_reuseNonZeroedOutput);  
		}
		else  {
			outputBlock.allocateDenseBlock();
		}
		outputBlock.setNonZeros(-1);

		if(DMLScript.STATISTICS)
			Statistics.incrementAllocationTime(System.nanoTime()-start, false);
		return outputBlock;
	}
}
