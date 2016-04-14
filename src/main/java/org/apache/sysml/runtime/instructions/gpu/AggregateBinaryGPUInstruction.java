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
package org.apache.sysml.runtime.instructions.gpu;

import org.apache.sysml.parser.Expression.DataType;
import org.apache.sysml.parser.Expression.ValueType;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.controlprogram.context.GPUContext;
import org.apache.sysml.runtime.functionobjects.Multiply;
import org.apache.sysml.runtime.functionobjects.Plus;
import org.apache.sysml.runtime.instructions.InstructionUtils;
import org.apache.sysml.runtime.instructions.cp.BinaryCPInstruction;
import org.apache.sysml.runtime.instructions.cp.CPOperand;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.operators.AggregateBinaryOperator;
import org.apache.sysml.runtime.matrix.operators.AggregateOperator;
import org.apache.sysml.runtime.matrix.operators.Operator;

public class AggregateBinaryGPUInstruction extends BinaryCPInstruction
{
	
	public AggregateBinaryGPUInstruction(Operator op, CPOperand in1, CPOperand in2, CPOperand out, 
			String opcode, String istr, boolean isLeftTransposed, boolean isRightTransposed){
		super(op, in1, in2, out, opcode, istr);
		_cptype = CPINSTRUCTION_TYPE.AggregateBinary;
		this.isLeftTransposed = isLeftTransposed;
		this.isRightTransposed = isRightTransposed;
	}

	boolean isLeftTransposed;
	boolean isRightTransposed;
	
	/**
	 * 
	 * @param str
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static AggregateBinaryGPUInstruction parseInstruction( String str ) 
		throws DMLRuntimeException 
	{
		CPOperand in1 = new CPOperand("", ValueType.UNKNOWN, DataType.UNKNOWN);
		CPOperand in2 = new CPOperand("", ValueType.UNKNOWN, DataType.UNKNOWN);
		CPOperand out = new CPOperand("", ValueType.UNKNOWN, DataType.UNKNOWN);

		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		String opcode = parts[0];

		if ( !opcode.equalsIgnoreCase("ba+*")) {
			throw new DMLRuntimeException("AggregateBinaryInstruction.parseInstruction():: Unknown opcode " + opcode);
		}
		
		InstructionUtils.checkNumFields( parts, 5 );
		in1.split(parts[1]);
		in2.split(parts[2]);
		out.split(parts[3]);
		
		boolean isLeftTransposed = Boolean.parseBoolean(parts[4]);
		boolean isRightTransposed = Boolean.parseBoolean(parts[5]);
		
		AggregateOperator agg = new AggregateOperator(0, Plus.getPlusFnObject());
		AggregateBinaryOperator aggbin = new AggregateBinaryOperator(Multiply.getMultiplyFnObject(), agg, 1);
		return new AggregateBinaryGPUInstruction(aggbin, in1, in2, out, opcode, str, isLeftTransposed, isRightTransposed);	
	}
	
	@Override
	public void processInstruction(ExecutionContext ec) 
		throws DMLRuntimeException
	{	
		AggregateBinaryOperator op = (AggregateBinaryOperator) _optr;
		if( !(op.binaryFn instanceof Multiply && op.aggOp.increOp.fn instanceof Plus) ) {
			throw new DMLRuntimeException("Unsupported binary aggregate operation: ("+op.binaryFn+", "+op.aggOp+").");
		}
		
		//get inputs
		MatrixBlock m1 = ec.getMatrixInputForGPUInstruction(input1.getName());
        MatrixBlock m2 = ec.getMatrixInputForGPUInstruction(input2.getName());
		
        //compute matrix multiplication
        int rlen = isLeftTransposed ? m1.getNumColumns() : m1.getNumRows();
        int clen = isRightTransposed ? m2.getNumRows() : m2.getNumColumns();
        MatrixBlock soresBlock = new MatrixBlock(rlen, clen, false);
        GPUContext gpuCtx = GPUContext.getCurrentContext();
        if(gpuCtx != null) {
        	gpuCtx.prepareOutput(soresBlock);
        	gpuCtx.matmult(m1, m2, soresBlock, isLeftTransposed, isRightTransposed);
        	soresBlock.setNonZeros(rlen*clen); // Worst case estimate
        	soresBlock.setNumRows(rlen);
        	soresBlock.setNumColumns(clen);
        }
        else {
			throw new DMLRuntimeException("GPUContext is not initialized");
		}
			
		//release inputs/outputs
		ec.releaseMatrixInputForGPUInstruction(input1.getName());
		ec.releaseMatrixInputForGPUInstruction(input2.getName());
		ec.setMatrixOutputForGPUInstruction(output.getName(), soresBlock);
	}
}

