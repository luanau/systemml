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

package org.apache.sysml.hops;

import java.util.ArrayList;

import org.apache.sysml.api.DMLScript;
import org.apache.sysml.conf.ConfigurationManager;
import org.apache.sysml.hops.rewrite.HopRewriteUtils;
import org.apache.sysml.lops.ConvolutionTransform;
import org.apache.sysml.lops.Lop;
import org.apache.sysml.lops.LopsException;
import org.apache.sysml.lops.LopProperties.ExecType;
import org.apache.sysml.parser.Expression.DataType;
import org.apache.sysml.parser.Expression.ValueType;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.matrix.MatrixCharacteristics;
import org.apache.sysml.runtime.util.ConvolutionUtils;

public class ConvolutionOp extends Hop 
{
	public static boolean FORCE_DIST_SORT_INDEXES = false;
	
	public boolean bSortSPRewriteApplicable = false;
	
	private Hop.ConvOp op;

	private ConvolutionOp() {
		//default constructor for clone
	}
	
	public ConvolutionOp(String l, DataType dt, ValueType vt, ConvOp o, Hop inp)
	{
		super(l, dt, vt);
		op = o;
		getInput().add(0, inp);
		inp.getParent().add(this);
		
		//compute unknown dims and nnz
		refreshSizeInformation();
	}
	
	
	public ConvolutionOp(String l, DataType dt, ValueType vt, ConvOp o, ArrayList<Hop> inp) 
	{
		super(l, dt, vt);
		op = o;
		
		for( int i=0; i<inp.size(); i++ ) {
			Hop in = inp.get(i);
			getInput().add(i, in);
			in.getParent().add(this);
		}
		
		//compute unknown dims and nnz
		refreshSizeInformation();
	}

	public ConvOp getOp()
	{
		return op;
	}
	
	@Override
	public String getOpString() {
		String s = new String("");
		s += "r(" + HopsTransf2String.get(op) + ")";
		return s;
	}

	@Override
	public Lop constructLops()
		throws HopsException, LopsException 
	{
		//return already created lops
		if( getLops() != null )
			return getLops();

		
		ExecType et = ExecType.INVALID;
		ArrayList<Hop> inputs = getInput();
		
		if(DMLScript.USE_GPU) {
			Hop parent = getInput().get(0);
			if(op == ConvOp.RESHAPE_COL && 
					parent instanceof AggBinaryOp 
					&& parent.getInput().get(1) instanceof ConvolutionOp
					&& ((ConvolutionOp) parent.getInput().get(1)).getOp() == ConvOp.IM2COL) {
				// Can be fused as GPU DIRECT_CONV2D
				et = ExecType.GPU;
				this.op = ConvOp.DIRECT_CONV2D;
				inputs = new ArrayList<Hop>();
				Hop image = parent.getInput().get(1).getInput().get(0);
				Hop filter = parent.getInput().get(0);
				inputs.add(image);
				inputs.add(filter);
				for(int i = 1; i < getInput().size(); i++){
					inputs.add(getInput().get(i));
				}
			}
		}
		
		if(et == ExecType.INVALID)
			et = optFindExecType();
		
		switch( op )
		{
			case IM2COL:
			case RESHAPE_COL:
			case ROTATE180:
			case COL2IM:
			case POOLING_PRE_RESHAPE:
			case POOLING_POST_RESHAPE:
			case POOLING_BACKWARD_RESHAPE:
			case MAX_POOLING:
			case MAX_POOLING_BACKWARD:
			case DIRECT_CONV2D:
			{	
				if( et == ExecType.CP || et == ExecType.GPU)
				{
					setLops(constructConvolutionLops(et, inputs));
					break;
				}
				else {
					throw new HopsException("Unimplemented ConvolutionOp for execution type: " + et.name());
				}
				// break;
			}
			default: 
				throw new HopsException("Unsupported lops construction for operation type '"+op+"'.");
		}
		
		//add reblock/checkpoint lops if necessary
		constructAndSetLopsDataFlowProperties();
				
		return getLops();
	}
	
	private Lop constructConvolutionLops(ExecType et, ArrayList<Hop> inputs) throws HopsException, LopsException {
		int expectedNumInputs = 13;
		if(op == ConvOp.POOLING_BACKWARD_RESHAPE || op == ConvOp.MAX_POOLING_BACKWARD
				|| op == ConvOp.DIRECT_CONV2D) {
			expectedNumInputs = 14;
		}
		
		if(inputs.size() != expectedNumInputs) {
			throw new HopsException("Incorrect number of inputs for " + op.name());
		}
		
		Lop in = inputs.get(0).constructLops();
		ConvolutionTransform transform1 = new ConvolutionTransform( in, 
				HopsConv2Lops.get(op), getDataType(), getValueType(), et);
		setOutputDimensions(transform1);
		setLineNumbers(transform1);
		
		// stride1, stride2, padding1, padding2  
		// input_shape1, input_shape2, input_shape3, input_shape4, 
		// filter_shape1, filter_shape2, filter_shape3, filter_shape4
		for( int i=1; i <= (expectedNumInputs-1); i++ )
		{
			Lop ltmp = inputs.get(i).constructLops();
			transform1.addInput(ltmp);
			ltmp.addOutput(transform1);
		}
		transform1.setLevel(); //force order of added lops
		return transform1;
	}

			
	@Override
	protected double computeOutputMemEstimate( long dim1, long dim2, long nnz )
	{		
		//no dedicated mem estimation per op type, because always propagated via refreshSizeInformation
		double sparsity = OptimizerUtils.getSparsity(dim1, dim2, nnz);
		return OptimizerUtils.estimateSizeExactSparsity(dim1, dim2, sparsity);
	}
	
	@Override
	protected double computeIntermediateMemEstimate( long dim1, long dim2, long nnz )
	{	
		//default: no intermediate memory requirements
		return 0;
	}
	
	@Override
	protected long[] inferOutputCharacteristics( MemoTable memo )
	{
		long[] ret = null;
	
		Hop input = getInput().get(0);
		MatrixCharacteristics mc = memo.getAllInputStats(input);
			
		switch(op) 
		{
//			case DIAG:
//			{
//				// NOTE: diag is overloaded according to the number of columns of the input
//				
//				long k = mc.getRows(); 
//				
//				// CASE a) DIAG V2M
//				// input is a [1,k] or [k,1] matrix, and output is [k,k] matrix
//				// #nnz in output is in the worst case k => sparsity = 1/k
//				if( k == 1 )
//					ret = new long[]{k, k, ((mc.getNonZeros()>=0) ? mc.getNonZeros() : k)};
//				
//				// CASE b) DIAG M2V
//				// input is [k,k] matrix and output is [k,1] matrix
//				// #nnz in the output is likely to be k (a dense matrix)		
//				if( k > 1 )
//					ret = new long[]{k, 1, ((mc.getNonZeros()>=0) ? Math.min(k,mc.getNonZeros()) : k) };
//				
//				break;		
//			}
			case IM2COL:
			case RESHAPE_COL:
			case ROTATE180:
			case COL2IM:
				// TODO:
		}	
		
		return ret;
	}
	

	@Override
	public boolean allowsAllExecTypes()
	{
		return true;
	}
	
	@Override
	protected ExecType optFindExecType() throws HopsException {
		
		checkAndSetForcedPlatform();
	
		ExecType REMOTE = OptimizerUtils.isSparkExecutionMode() ? ExecType.SPARK : ExecType.MR;
		
		if( _etypeForced != null ) 			
		{
			_etype = _etypeForced;
		}
		else 
		{	
			if ( OptimizerUtils.isMemoryBasedOptLevel() ) {
				_etype = findExecTypeByMemEstimate();
			}
			// Choose CP, if the input dimensions are below threshold or if the input is a vector
			else if ( getInput().get(0).areDimsBelowThreshold() || getInput().get(0).isVector() )
			{
				_etype = ExecType.CP;
			}
			else 
			{
				_etype = REMOTE;
			}
			
			//check for valid CP dimensions and matrix size
			checkAndSetInvalidCPDimsAndSize();
		}
		
		//mark for recompile (forever)
		if( ConfigurationManager.isDynamicRecompilation() && !dimsKnown(true) && _etype==REMOTE )
			setRequiresRecompile();
	
		return _etype;
	}
	
	long N = -1; long C = -1; long H = -1; long W = -1;
	long K = -1; long R = -1; long S = -1;
	long stride1 = -1;
	long stride2 = -1;
	long padding1 = -1;
	long padding2 = -1;
	long P = -1; long Q = -1;
	// stride1, stride2, padding1, padding2  
	// input_shape1, input_shape2, input_shape3, input_shape4, 
	// filter_shape1, filter_shape2, filter_shape3, filter_shape4
	void parseInput() {
		// For pooling: x,stride,stride,pad,pad,numImg,numChannels,imgSize,imgSize,1,1,poolSize1,poolSize2)
		try {
			stride1 = extractValue(getInput().get(1));
			stride2 = extractValue(getInput().get(2));
			padding1 = extractValue(getInput().get(3));
			padding2 = extractValue(getInput().get(4));
			N = extractValue(getInput().get(5));
			C = extractValue(getInput().get(6));
			H = extractValue(getInput().get(7));
			W = extractValue(getInput().get(8));
			K = extractValue(getInput().get(9));
			C = (C <= 0) ? extractValue(getInput().get(10)) : C;
			R = extractValue(getInput().get(11));
			S = extractValue(getInput().get(12));
			P = ConvolutionUtils.getP(H, R, stride1, padding1);
			Q = ConvolutionUtils.getQ(W, S, stride2, padding2);
		} catch (DMLRuntimeException e) {
			// throw new RuntimeException("Error parsing the input for Convop", e);
		}
	}
	
	long getExtractedVal(long val1, long val2) {
		if(val1 == -1 || val2 == -1) {
			return -1;
		}
		return val1*val2;
	}
	
	long getExtractedVal(long val1, long val2, long val3) {
		if(val1 == -1 || val2 == -1 || val3 == -1) {
			return -1;
		}
		return val1*val2*val3;
	}
	
	@Override
	public void refreshSizeInformation()
	{
		Hop input1 = getInput().get(0);
		
//		switch(op) 
//		{
//			case IM2COL:
//			{
//				parseInput();
//				_dim1 = getExtractedVal(C, R, S);
//				_dim2 = getExtractedVal(N, P, Q);
//				if(input1.getNnz() >= 0) {
//					// long approxNumPaddedZeros = N*C*(2*(P*R + Q*S));
//					// long numZerosInOriginalImage = (N*C*H*W - input1.getNnz());
//					// long conservativeEstNumZeros = (numZerosInOriginalImage + approxNumPaddedZeros);
//					// Worst-case estimates (assuming only nnz are replicated):
//					// TODO:
//					_nnz = _dim1*_dim2; // - numZerosInOriginalImage; 
//				}
//				
//				break;
//			}
//			case COL2IM:
//			{
//				parseInput();
//				// Set _dim1, _dim2 and if possible _nnz (use input1.getNnz())
//				_dim1 = N;
//				_dim2 = getExtractedVal(C, H, W);
//				_nnz = _dim1*_dim2;
//				break;
//			}
//			case RESHAPE_COL:
//			{
//				parseInput();
//				_dim1 = N;
//				_dim2 = getExtractedVal(K, P, Q);
//				// TODO: nnz
//				_nnz = _dim1*_dim2;
//				break;
//			}
//			case ROTATE180:
//			{
//				parseInput();
//				_dim1 = K;
//				_dim2 = getExtractedVal(N, P, Q);
//				// TODO: nnz
//				_nnz = _dim1*_dim2;
//				break;
//			}
//			case POOLING_PRE_RESHAPE:
//			{
//				parseInput();
//				// Set _dim1, _dim2 and if possible _nnz (use input1.getNnz())
//				_dim1 = getExtractedVal(N, C);
//				_dim2 = getExtractedVal(H, W);
//				_nnz = _dim1*_dim2;
//				break;
//			}
//			case POOLING_POST_RESHAPE:
//			{
//				parseInput();
//				// Set _dim1, _dim2 and if possible _nnz (use input1.getNnz())
//				_dim1 = N;
//				_dim2 = getExtractedVal(C, P, Q);
//				_nnz = _dim1*_dim2;
//				break;
//			}
//			case POOLING_BACKWARD_RESHAPE:
//			{
//				parseInput();
//				// Set _dim1, _dim2 and if possible _nnz (use input1.getNnz())
//				_dim1 = getExtractedVal(C, R, S);
//				_dim2 = getExtractedVal(N, P, Q);
//				_nnz = _dim1*_dim2;
//				break;
//			}
//			case MAX_POOLING:
//			{
//				parseInput();
//				// Set _dim1, _dim2 and if possible _nnz (use input1.getNnz())
//				_dim1 = N;
//				_dim2 = getExtractedVal(C, P, Q);
//				_nnz = _dim1*_dim2;
//				break;
//			}
//			case MAX_POOLING_BACKWARD:
//			{
//				parseInput();
//				// Set _dim1, _dim2 and if possible _nnz (use input1.getNnz())
//				_dim1 = N;
//				_dim2 = getExtractedVal(C, H, W);
//				_nnz = _dim1*_dim2;
//				break;
//			}
//			default:
//				throw new RuntimeException("The sizes are not refreshed for " + op.name());
//		}
//		if(_dim1 == -1 || _dim2 == -1) {
//			_nnz = -1;
//		}
	}
	
	private long extractValue(Hop hop) throws DMLRuntimeException {
		if(hop instanceof LiteralOp)
			return (long) HopRewriteUtils.getDoubleValueSafe((LiteralOp)hop);
		throw new DMLRuntimeException("Cannot extract value");
	}
	
	@Override
	public Object clone() throws CloneNotSupportedException 
	{
		ConvolutionOp ret = new ConvolutionOp();	
		
		//copy generic attributes
		ret.clone(this, false);
		
		//copy specific attributes
		ret.op = op;
		
		return ret;
	}
	
	@Override
	public boolean compare( Hop that )
	{
		if( !(that instanceof ConvolutionOp) )
			return false;
		
		ConvolutionOp that2 = (ConvolutionOp)that;		
		boolean ret =  (op == that2.op)
				    && (getInput().size()==that.getInput().size());
				
		//compare all childs (see reshape, sort)
		if( ret ) //sizes matched
			for( int i=0; i<_input.size(); i++ )
				ret &= getInput().get(i) == that2.getInput().get(i);
		
		return ret;
	}
	
	
	@Override
	public void printMe() throws HopsException 
	{
		if (LOG.isDebugEnabled()){
			if (getVisited() != VisitStatus.DONE) {
				super.printMe();
				LOG.debug("  Operation: " + op);
				for (Hop h : getInput()) {
					h.printMe();
				}
			}
			setVisited(VisitStatus.DONE);
		}
	}
}