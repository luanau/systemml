package org.apache.sysml.runtime.instructions.gpu;

import org.apache.sysml.parser.Expression.ValueType;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.functionobjects.SwapIndex;
import org.apache.sysml.runtime.instructions.InstructionUtils;
import org.apache.sysml.runtime.instructions.cp.BooleanObject;
import org.apache.sysml.runtime.instructions.cp.CPOperand;
import org.apache.sysml.runtime.instructions.gpu.context.ExecutionConfig;
import org.apache.sysml.runtime.instructions.gpu.context.GPUContext;
import org.apache.sysml.runtime.matrix.data.LibMatrixCUDA;
import org.apache.sysml.runtime.matrix.operators.Operator;
import org.apache.sysml.runtime.matrix.operators.ReorgOperator;
import org.apache.sysml.utils.GPUStatistics;

import jcuda.Pointer;

public class MatrixReshapeGPUInstruction extends GPUInstruction {
	
	private final CPOperand _input;
	private final CPOperand _output;
	private final CPOperand _opRows;
	private final CPOperand _opCols;
	private final CPOperand _opByRow;
	
	protected MatrixReshapeGPUInstruction(Operator op, String opcode, String istr, 
			CPOperand in1, CPOperand in2, CPOperand in3, CPOperand in4, CPOperand out) {
		super(op, opcode, istr);
		_input = in1;
		_opRows = in2;
		_opCols = in3;
		_opByRow = in4;
		_output = out;
	}
	
	public static MatrixReshapeGPUInstruction parseInstruction ( String str ) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		InstructionUtils.checkNumFields( parts, 5 );
		String opcode = parts[0];
		CPOperand in1 = new CPOperand(parts[1]);
		CPOperand in2 = new CPOperand(parts[2]);
		CPOperand in3 = new CPOperand(parts[3]);
		CPOperand in4 = new CPOperand(parts[4]);
		CPOperand out = new CPOperand(parts[5]);
		if(!opcode.equalsIgnoreCase("rshape"))
			throw new DMLRuntimeException("Unknown opcode while parsing an MatrixReshapeGPUInstruction: " + str);
		else
			return new MatrixReshapeGPUInstruction(new ReorgOperator(SwapIndex.getSwapIndexFnObject()), opcode, str, in1, in2, in3, in4, out);
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		int rows = (int)ec.getScalarInput(_opRows.getName(), _opRows.getValueType(), _opRows.isLiteral()).getLongValue(); //save cast
		int cols = (int)ec.getScalarInput(_opCols.getName(), _opCols.getValueType(), _opCols.isLiteral()).getLongValue(); //save cast
		BooleanObject byRow = (BooleanObject) ec.getScalarInput(_opByRow.getName(), ValueType.BOOLEAN, _opByRow.isLiteral());
		
		GPUStatistics.incrementNoOfExecutedGPUInst();
		String instName = getExtendedOpcode();
		GPUContext gCtx = ec.getGPUContext(0); 
		MatrixObject mat = getMatrixInputForGPUInstruction(ec, _input.getName());
		if(rows*cols != mat.getNumRows()*mat.getNumColumns()) {
			throw new DMLRuntimeException("Incorrect number of rows and cols in rshape instruction");
		}
		Pointer inPtr = LibMatrixCUDA.getDensePointer(gCtx, mat, instName);
		MatrixObject out = LibMatrixCUDA.getDenseMatrixOutputForGPUInstruction(ec, instName, _output.getName(), rows, cols);
		Pointer outPtr = LibMatrixCUDA.getDensePointer(gCtx, out, instName);
		if(byRow.getBooleanValue()) {
			// byrow = TRUE
			LibMatrixCUDA.deviceCopy(instName, inPtr, outPtr, LibMatrixCUDA.toInt(mat.getNumRows()), LibMatrixCUDA.toInt(mat.getNumColumns()));
		}
		else  {
			// byrow = FALSE 
			LibMatrixCUDA.getCudaKernels(gCtx).launchKernel("colwise_reshape", 
				ExecutionConfig.getConfigForSimpleVectorOperations(LibMatrixCUDA.toInt(rows*cols)),
				inPtr, outPtr, LibMatrixCUDA.toInt(rows*cols), 
				LibMatrixCUDA.toInt(mat.getNumRows()), LibMatrixCUDA.toInt(mat.getNumColumns()),
				rows, cols);
		}
		ec.releaseMatrixInputForGPUInstruction(_input.getName());
		ec.releaseMatrixOutputForGPUInstruction(_output.getName());
	}

}
