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
package org.apache.sysml.runtime.instructions;

import java.util.HashMap;

import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.instructions.gpu.AggregateBinaryGPUInstruction;
import org.apache.sysml.runtime.instructions.gpu.ArithmeticBinaryGPUInstruction;
import org.apache.sysml.runtime.instructions.gpu.BuiltinUnaryGPUInstruction;
import org.apache.sysml.runtime.instructions.gpu.ConvolutionGPUInstruction;
import org.apache.sysml.runtime.instructions.gpu.GPUInstruction;
import org.apache.sysml.runtime.instructions.gpu.MatrixMatrixAxpyGPUInstruction;
import org.apache.sysml.runtime.instructions.gpu.GPUInstruction.GPUINSTRUCTION_TYPE;
import org.apache.sysml.runtime.instructions.gpu.MMTSJGPUInstruction;
import org.apache.sysml.runtime.instructions.gpu.ReorgGPUInstruction;
import org.apache.sysml.runtime.instructions.gpu.context.AggregateUnaryGPUInstruction;

public class GPUInstructionParser  extends InstructionParser 
{
	public static final HashMap<String, GPUINSTRUCTION_TYPE> String2GPUInstructionType;
	static {
		String2GPUInstructionType = new HashMap<String, GPUINSTRUCTION_TYPE>();

		// Neural Network Operators
		String2GPUInstructionType.put( "relu_backward",          GPUINSTRUCTION_TYPE.Convolution);
		String2GPUInstructionType.put( "conv2d",                 GPUINSTRUCTION_TYPE.Convolution);
		String2GPUInstructionType.put( "conv2d_backward_filter", GPUINSTRUCTION_TYPE.Convolution);
		String2GPUInstructionType.put( "conv2d_backward_data",   GPUINSTRUCTION_TYPE.Convolution);
		String2GPUInstructionType.put( "maxpooling",             GPUINSTRUCTION_TYPE.Convolution);
		String2GPUInstructionType.put( "maxpooling_backward",    GPUINSTRUCTION_TYPE.Convolution);
		String2GPUInstructionType.put( "bias_add",    			 GPUINSTRUCTION_TYPE.Convolution);

		// Matrix Multiply Operators
		String2GPUInstructionType.put( "ba+*",                   GPUINSTRUCTION_TYPE.AggregateBinary);
		String2GPUInstructionType.put( "tsmm",                   GPUINSTRUCTION_TYPE.MMTSJ);

		// Reorg/Transpose
		String2GPUInstructionType.put( "r'",                   	 GPUINSTRUCTION_TYPE.Reorg);
	
		// Binary Cellwise
		String2GPUInstructionType.put( "+"    , GPUINSTRUCTION_TYPE.ArithmeticBinary);
		String2GPUInstructionType.put( "-"    , GPUINSTRUCTION_TYPE.ArithmeticBinary);
		String2GPUInstructionType.put( "*"    , GPUINSTRUCTION_TYPE.ArithmeticBinary);
		String2GPUInstructionType.put( "/"    , GPUINSTRUCTION_TYPE.ArithmeticBinary);
		String2GPUInstructionType.put( "%%"   , GPUINSTRUCTION_TYPE.ArithmeticBinary);
		String2GPUInstructionType.put( "%/%"  , GPUINSTRUCTION_TYPE.ArithmeticBinary);
		String2GPUInstructionType.put( "^"    , GPUINSTRUCTION_TYPE.ArithmeticBinary);
		String2GPUInstructionType.put( "1-*"  , GPUINSTRUCTION_TYPE.ArithmeticBinary); //special * case
		String2GPUInstructionType.put( "^2"   , GPUINSTRUCTION_TYPE.ArithmeticBinary); //special ^ case
		String2GPUInstructionType.put( "*2"   , GPUINSTRUCTION_TYPE.ArithmeticBinary); //special * case
		String2GPUInstructionType.put( "-nz"  , GPUINSTRUCTION_TYPE.ArithmeticBinary); //special - case
		String2GPUInstructionType.put( "+*"  , GPUINSTRUCTION_TYPE.ArithmeticBinary); 
		String2GPUInstructionType.put( "-*"  , GPUINSTRUCTION_TYPE.ArithmeticBinary); 
		
		
		String2GPUInstructionType.put( "sel+"  , GPUINSTRUCTION_TYPE.BuiltinUnary);

		// Aggregate Unary
		String2GPUInstructionType.put( "ua+"	 , GPUINSTRUCTION_TYPE.AggregateUnary);
		String2GPUInstructionType.put( "uak+"	 , GPUINSTRUCTION_TYPE.AggregateUnary);
		String2GPUInstructionType.put( "uar+"	 , GPUINSTRUCTION_TYPE.AggregateUnary);
		String2GPUInstructionType.put( "uark+"	 , GPUINSTRUCTION_TYPE.AggregateUnary);
		String2GPUInstructionType.put( "uac+"	 , GPUINSTRUCTION_TYPE.AggregateUnary);
		String2GPUInstructionType.put( "uack+"	 , GPUINSTRUCTION_TYPE.AggregateUnary);
		String2GPUInstructionType.put( "uamean"	 , GPUINSTRUCTION_TYPE.AggregateUnary);
		String2GPUInstructionType.put( "uamax"	 , GPUINSTRUCTION_TYPE.AggregateUnary);
		String2GPUInstructionType.put( "uamin"	 , GPUINSTRUCTION_TYPE.AggregateUnary);
	}
	
	public static GPUInstruction parseSingleInstruction (String str ) 
		throws DMLRuntimeException 
	{
		if ( str == null || str.isEmpty() )
			return null;

		GPUINSTRUCTION_TYPE cptype = InstructionUtils.getGPUType(str); 
		if ( cptype == null ) 
			throw new DMLRuntimeException("Unable derive cptype for instruction: " + str);
		GPUInstruction cpinst = parseSingleInstruction(cptype, str);
		if ( cpinst == null )
			throw new DMLRuntimeException("Unable to parse instruction: " + str);
		return cpinst;
	}
	
	public static GPUInstruction parseSingleInstruction ( GPUINSTRUCTION_TYPE gputype, String str ) 
		throws DMLRuntimeException 
	{
		if( str == null || str.isEmpty() ) 
			return null;	
		if( gputype == null )
			throw new DMLRuntimeException("The instruction is not GPU-enabled:" + str);
		
		switch(gputype) {
			case AggregateUnary:
				return AggregateUnaryGPUInstruction.parseInstruction(str);

			case AggregateBinary:
				return AggregateBinaryGPUInstruction.parseInstruction(str);
			
			case BuiltinUnary:
				return BuiltinUnaryGPUInstruction.parseInstruction(str);
			
			case Convolution:
				return ConvolutionGPUInstruction.parseInstruction(str);
				
			case MMTSJ:
				return MMTSJGPUInstruction.parseInstruction(str);
				
			case Reorg:
				return ReorgGPUInstruction.parseInstruction(str);
				
			case ArithmeticBinary:
				String opcode = InstructionUtils.getOpCode(str);
				if( opcode.equals("+*") || opcode.equals("-*")  )
					return MatrixMatrixAxpyGPUInstruction.parseInstruction(str);
				else
					return ArithmeticBinaryGPUInstruction.parseInstruction(str);
				
			default: 
				throw new DMLRuntimeException("Invalid GPU Instruction Type: " + gputype );
		}
	}	
}