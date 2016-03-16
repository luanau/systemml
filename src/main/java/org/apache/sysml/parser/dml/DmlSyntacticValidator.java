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

package org.apache.sysml.parser.dml;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.sysml.api.DMLScript;
import org.apache.sysml.parser.AParserWrapper;
import org.apache.sysml.parser.AssignmentStatement;
import org.apache.sysml.parser.BinaryExpression;
import org.apache.sysml.parser.ConditionalPredicate;
import org.apache.sysml.parser.DMLProgram;
import org.apache.sysml.parser.DataIdentifier;
import org.apache.sysml.parser.Expression;
import org.apache.sysml.parser.Expression.BinaryOp;
import org.apache.sysml.parser.Expression.DataType;
import org.apache.sysml.parser.Expression.ValueType;
import org.apache.sysml.parser.ExternalFunctionStatement;
import org.apache.sysml.parser.ForStatement;
import org.apache.sysml.parser.FunctionCallIdentifier;
import org.apache.sysml.parser.FunctionStatement;
import org.apache.sysml.parser.IfStatement;
import org.apache.sysml.parser.ImportStatement;
import org.apache.sysml.parser.IndexedIdentifier;
import org.apache.sysml.parser.IntIdentifier;
import org.apache.sysml.parser.IterablePredicate;
import org.apache.sysml.parser.LanguageException;
import org.apache.sysml.parser.ExpressionList;
import org.apache.sysml.parser.ParForStatement;
import org.apache.sysml.parser.ParameterExpression;
import org.apache.sysml.parser.ParseException;
import org.apache.sysml.parser.PathStatement;
import org.apache.sysml.parser.Statement;
import org.apache.sysml.parser.StatementBlock;
import org.apache.sysml.parser.WhileStatement;
import org.apache.sysml.parser.common.CommonSyntacticValidator;
import org.apache.sysml.parser.common.CustomErrorListener;
import org.apache.sysml.parser.common.ExpressionInfo;
import org.apache.sysml.parser.common.StatementInfo;
import org.apache.sysml.parser.dml.DmlParser.AddSubExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.AssignmentStatementContext;
import org.apache.sysml.parser.dml.DmlParser.AtomicExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.BooleanAndExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.BooleanNotExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.BooleanOrExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.BuiltinFunctionExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.CommandlineParamExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.CommandlinePositionExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.ConstDoubleIdExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.ConstFalseExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.ConstIntIdExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.ConstStringIdExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.ConstTrueExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.DataIdExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.DataIdentifierContext;
import org.apache.sysml.parser.dml.DmlParser.ExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.ExternalFunctionDefExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.ForStatementContext;
import org.apache.sysml.parser.dml.DmlParser.FunctionCallAssignmentStatementContext;
import org.apache.sysml.parser.dml.DmlParser.FunctionCallMultiAssignmentStatementContext;
import org.apache.sysml.parser.dml.DmlParser.FunctionStatementContext;
import org.apache.sysml.parser.dml.DmlParser.IfStatementContext;
import org.apache.sysml.parser.dml.DmlParser.IfdefAssignmentStatementContext;
import org.apache.sysml.parser.dml.DmlParser.ImportStatementContext;
import org.apache.sysml.parser.dml.DmlParser.IndexedExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.InternalFunctionDefExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.IterablePredicateColonExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.IterablePredicateSeqExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.MatrixDataTypeCheckContext;
import org.apache.sysml.parser.dml.DmlParser.MatrixMulExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.Ml_typeContext;
import org.apache.sysml.parser.dml.DmlParser.ModIntDivExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.MultDivExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.MultiIdExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.OptionalUpperIndexExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.ParForStatementContext;
import org.apache.sysml.parser.dml.DmlParser.ParameterizedExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.PathStatementContext;
import org.apache.sysml.parser.dml.DmlParser.PowerExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.ProgramrootContext;
import org.apache.sysml.parser.dml.DmlParser.RelationalExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.SimpleDataIdentifierExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.StatementContext;
import org.apache.sysml.parser.dml.DmlParser.StrictParameterizedExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.StrictParameterizedKeyValueStringContext;
import org.apache.sysml.parser.dml.DmlParser.TensorIndexedExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.TypedArgNoAssignContext;
import org.apache.sysml.parser.dml.DmlParser.UnaryExpressionContext;
import org.apache.sysml.parser.dml.DmlParser.ValueTypeContext;
import org.apache.sysml.parser.dml.DmlParser.WhileStatementContext;


public class DmlSyntacticValidator extends CommonSyntacticValidator implements DmlListener {

	public DmlSyntacticValidator(CustomErrorListener errorListener, HashMap<String,String> argVals) {
		super(errorListener, argVals);
	}

	@Override public String namespaceResolutionOp() { return "::"; }
	@Override public String trueStringLiteral() { return "TRUE"; }
	@Override public String falseStringLiteral() { return "FALSE"; }

	protected ArrayList<ParameterExpression> getParameterExpressionList(List<ParameterizedExpressionContext> paramExprs) {
		ArrayList<ParameterExpression> retVal = new ArrayList<ParameterExpression>();
		for(ParameterizedExpressionContext ctx : paramExprs) {
			String paramName = null;
			if(ctx.paramName != null && ctx.paramName.getText() != null && !ctx.paramName.getText().isEmpty()) {
				paramName = ctx.paramName.getText();
			}
			ParameterExpression myArg = new ParameterExpression(paramName, ctx.paramVal.info.expr);
			retVal.add(myArg);
		}
		return retVal;
	}

	@Override
	public void enterEveryRule(ParserRuleContext arg0) {
		if(arg0 instanceof StatementContext) {
			if(((StatementContext) arg0).info == null) {
				((StatementContext) arg0).info = new StatementInfo();
			}
		}
		if(arg0 instanceof FunctionStatementContext) {
			if(((FunctionStatementContext) arg0).info == null) {
				((FunctionStatementContext) arg0).info = new StatementInfo();
			}
		}
		if(arg0 instanceof ExpressionContext) {
			if(((ExpressionContext) arg0).info == null) {
				((ExpressionContext) arg0).info = new ExpressionInfo();
			}
		}
		if(arg0 instanceof DataIdentifierContext) {
			if(((DataIdentifierContext) arg0).dataInfo == null) {
				((DataIdentifierContext) arg0).dataInfo = new ExpressionInfo();
			}
		}
	}

	// -----------------------------------------------------------------
	// 			Binary, Unary & Relational Expressions
	// -----------------------------------------------------------------

	// For now do no type checking, let validation handle it.
	// This way parser doesn't have to open metadata file
	@Override
	public void exitAddSubExpression(AddSubExpressionContext ctx) {
		binaryExpressionHelper(ctx, ctx.left.info, ctx.right.info, ctx.info, ctx.op.getText());
	}

	@Override
	public void exitModIntDivExpression(ModIntDivExpressionContext ctx) {
		binaryExpressionHelper(ctx, ctx.left.info, ctx.right.info, ctx.info, ctx.op.getText());
	}

	@Override
	public void exitUnaryExpression(UnaryExpressionContext ctx) {
		unaryExpressionHelper(ctx, ctx.left.info, ctx.info, ctx.op.getText());
	}

	@Override
	public void exitMultDivExpression(MultDivExpressionContext ctx) {
		binaryExpressionHelper(ctx, ctx.left.info, ctx.right.info, ctx.info, ctx.op.getText());
	}
	@Override
	public void exitPowerExpression(PowerExpressionContext ctx) {
		binaryExpressionHelper(ctx, ctx.left.info, ctx.right.info, ctx.info, ctx.op.getText());
	}

	@Override
	public void exitMatrixMulExpression(MatrixMulExpressionContext ctx) {
		binaryExpressionHelper(ctx, ctx.left.info, ctx.right.info, ctx.info, ctx.op.getText());
	}

	@Override
	public void exitRelationalExpression(RelationalExpressionContext ctx) {
		relationalExpressionHelper(ctx, ctx.left.info, ctx.right.info, ctx.info, ctx.op.getText());
	}

	@Override
	public void exitBooleanAndExpression(BooleanAndExpressionContext ctx) {
		booleanExpressionHelper(ctx, ctx.left.info, ctx.right.info, ctx.info, ctx.op.getText());
	}

	@Override
	public void exitBooleanOrExpression(BooleanOrExpressionContext ctx) {
		booleanExpressionHelper(ctx, ctx.left.info, ctx.right.info, ctx.info, ctx.op.getText());
	}

	@Override
	public void exitBooleanNotExpression(BooleanNotExpressionContext ctx) {
		unaryBooleanExpressionHelper(ctx, ctx.left.info, ctx.info, ctx.op.getText());
	}

	@Override
	public void exitAtomicExpression(AtomicExpressionContext ctx) {
		ctx.info.expr = ctx.left.info.expr;
		setFileLineColumn(ctx.info.expr, ctx);
	}

	// -----------------------------------------------------------------
	// 			Constant Expressions
	// -----------------------------------------------------------------

	@Override
	public void exitConstFalseExpression(ConstFalseExpressionContext ctx) {
		booleanIdentifierHelper(ctx, false, ctx.info);
	}

	@Override
	public void exitConstTrueExpression(ConstTrueExpressionContext ctx) {
		booleanIdentifierHelper(ctx, true, ctx.info);
	}

	@Override
	public void exitConstDoubleIdExpression(ConstDoubleIdExpressionContext ctx) {
		constDoubleIdExpressionHelper(ctx, ctx.info);
	}

	@Override
	public void exitConstIntIdExpression(ConstIntIdExpressionContext ctx) {
		constIntIdExpressionHelper(ctx, ctx.info);
	}

	@Override
	public void exitConstStringIdExpression(ConstStringIdExpressionContext ctx) {
		constStringIdExpressionHelper(ctx, ctx.info);
	}


	// -----------------------------------------------------------------
	//          Identifier Based Expressions
	// -----------------------------------------------------------------

	@Override
	public void exitDataIdExpression(DataIdExpressionContext ctx) {
		exitDataIdExpressionHelper(ctx, ctx.info, ctx.dataIdentifier().dataInfo);
	}

	@Override
	public void exitSimpleDataIdentifierExpression(SimpleDataIdentifierExpressionContext ctx) {
		// This is either a function, or variable with namespace
		// By default, it assigns to a data type
		ctx.dataInfo.expr = new DataIdentifier(ctx.getText());
		setFileLineColumn(ctx.dataInfo.expr, ctx);
	}

	@Override
	public void exitIndexedExpression(IndexedExpressionContext ctx) {
		boolean isRowLower = (ctx.rowLower != null && !ctx.rowLower.isEmpty() && (ctx.rowLower.info.expr != null));
		boolean isRowUpper = (ctx.rowUpper != null && !ctx.rowUpper.isEmpty() && (ctx.rowUpper.info.expr != null));
		boolean isColLower = (ctx.colLower != null && !ctx.colLower.isEmpty() && (ctx.colLower.info.expr != null));
		boolean isColUpper = (ctx.colUpper != null && !ctx.colUpper.isEmpty() && (ctx.colUpper.info.expr != null));
		String name = ctx.name.getText();
		exitIndexedExpressionHelper(ctx, name, ctx.dataInfo,
				isRowLower ? ctx.rowLower.info : null,
				isRowUpper ? ctx.rowUpper.info : null,
				isColLower ? ctx.colLower.info : null,
				isColUpper ? ctx.colUpper.info : null);
	}


	// -----------------------------------------------------------------
	//          Command line parameters (begin with a '$')
	// -----------------------------------------------------------------

	@Override
	public void exitCommandlineParamExpression(CommandlineParamExpressionContext ctx) {
		handleCommandlineArgumentExpression(ctx);
	}

	@Override
	public void exitCommandlinePositionExpression(CommandlinePositionExpressionContext ctx) {
		handleCommandlineArgumentExpression(ctx);
	}

	private void handleCommandlineArgumentExpression(DataIdentifierContext ctx)
	{
		String varName = ctx.getText().trim();
		fillExpressionInfoCommandLineParameters(varName, ctx.dataInfo, ctx.start);

		if(ctx.dataInfo.expr == null) {
			if(!(ctx.parent instanceof IfdefAssignmentStatementContext)) {
				String msg = "The parameter " + varName + " either needs to be passed "
						+ "through commandline or initialized to default value.";
				if( AParserWrapper.IGNORE_UNSPECIFIED_ARGS ) {
					ctx.dataInfo.expr = getConstIdFromString(" ", ctx.start);
					raiseWarning(msg, ctx.start);
				}
				else {
					notifyErrorListeners(msg, ctx.start);
				}
			}
		}
	}


	// -----------------------------------------------------------------
	// 			"src" statment
	// -----------------------------------------------------------------

	@Override
	public void exitImportStatement(ImportStatementContext ctx)
	{
		//prepare import filepath
		String filePath = ctx.filePath.getText();
		String namespace = DMLProgram.DEFAULT_NAMESPACE;
		if(ctx.namespace != null && ctx.namespace.getText() != null && !ctx.namespace.getText().isEmpty()) {
			namespace = ctx.namespace.getText();
		}
		if((filePath.startsWith("\"") && filePath.endsWith("\"")) ||
				filePath.startsWith("'") && filePath.endsWith("'")) {
			filePath = filePath.substring(1, filePath.length()-1);
		}

		//concatenate working directory to filepath
		filePath = _workingDir + File.separator + filePath;

		DMLProgram prog = null;
		try {
			prog = (new DMLParserWrapper()).doParse(filePath, null, argVals);
		} catch (ParseException e) {
			notifyErrorListeners("Exception found during importing a program from file " + filePath, ctx.start);
			return;
		}
        // Custom logic whether to proceed ahead or not. Better than the current exception handling mechanism
		if(prog == null) {
			notifyErrorListeners("One or more errors found during importing a program from file " + filePath, ctx.start);
			return;
		}
		else {
			ctx.info.namespaces = new HashMap<String, DMLProgram>();
			ctx.info.namespaces.put(namespace, prog);
			ctx.info.stmt = new ImportStatement();
			((ImportStatement) ctx.info.stmt).setCompletePath(filePath);
			((ImportStatement) ctx.info.stmt).setFilePath(ctx.filePath.getText());
			((ImportStatement) ctx.info.stmt).setNamespace(namespace);
		}
	}

	// -----------------------------------------------------------------
	// 			Assignment Statement
	// -----------------------------------------------------------------

	@Override
	public void exitAssignmentStatement(AssignmentStatementContext ctx) {
		if(ctx.targetList == null) {
			notifyErrorListeners("incorrect parsing for assignment", ctx.start);
			return;
		}
		exitAssignmentStatementHelper(ctx, ctx.targetList.getText(), ctx.targetList.dataInfo, ctx.targetList.start, ctx.source.info, ctx.info);
	}


	// -----------------------------------------------------------------
	// 			Control Statements - Guards & Loops
	// -----------------------------------------------------------------

	@Override
	public ConvertedDMLSyntax convertToDMLSyntax(ParserRuleContext ctx, String namespace, String functionName, ArrayList<ParameterExpression> paramExpression, Token fnName) {
		return new ConvertedDMLSyntax(namespace, functionName, paramExpression);
	}


	@Override
	protected Expression handleLanguageSpecificFunction(ParserRuleContext ctx, String functionName,
			ArrayList<ParameterExpression> paramExpressions) {
		return null;
	}


	@Override
	public void exitFunctionCallAssignmentStatement(FunctionCallAssignmentStatementContext ctx) {

		Set<String> printStatements = new  HashSet<String>();
		printStatements.add("print");
		printStatements.add("stop");

		Set<String> outputStatements = new HashSet<String>();
		outputStatements.add("write");

		String [] fnNames = getQualifiedNames (ctx.name.getText());
		if (fnNames == null) {
			String errorMsg = "incorrect function name (only namespace " + namespaceResolutionOp() + " functionName allowed. Hint: If you are trying to use builtin functions, you can skip the namespace)";
			notifyErrorListeners(errorMsg, ctx.name);
			return;
		}
		String namespace = fnNames[0];
		String functionName = fnNames[1];
		
		ArrayList<ParameterExpression> paramExpression = getParameterExpressionList(ctx.paramExprs);

		if(functionName.equals("conv2d") || functionName.equals("conv2d_backward_filter") || functionName.equals("conv2d_backward_data")) {
			HashSet<String> expand = new HashSet<String>();
			expand.add("input_shape"); expand.add("filter_shape"); expand.add("stride"); expand.add("padding");
			paramExpression = expandListParams(paramExpression, expand, ctx);
		}
		
		boolean hasLHS = ctx.targetList != null;
		functionCallAssignmentStatementHelper(ctx, printStatements, outputStatements, hasLHS ? ctx.targetList.dataInfo.expr : null, ctx.info, ctx.name,
	 			hasLHS ? ctx.targetList.start : null, namespace, functionName, paramExpression, hasLHS);
	}



	@Override
	public void exitBuiltinFunctionExpression(BuiltinFunctionExpressionContext ctx) {
		// Double verification: verify passed function name is a (non-parameterized) built-in function.
		String[] names = getQualifiedNames(ctx.name.getText());
		if(names == null) {
			notifyErrorListeners("incorrect function name (only namespace " + namespaceResolutionOp() + " functionName allowed. Hint: If you are trying to use builtin functions, you can skip the namespace)", ctx.name);
			return;
		}
		String namespace = names[0];
		String functionName = names[1];

		ArrayList<ParameterExpression> paramExpression = getParameterExpressionList(ctx.paramExprs);

		ConvertedDMLSyntax convertedSyntax = convertToDMLSyntax(ctx, namespace, functionName, paramExpression, ctx.name);
		if(convertedSyntax == null) {
			return;
		}
		else {
			functionName = convertedSyntax.functionName;
			paramExpression = convertedSyntax.paramExpression;
		}
		final ExpressionInfo info = ctx.info;
		Action f = new Action() {
			@Override public void execute(Expression e) { info.expr = e; }
		};
		
		if(functionName.equals("conv2d")) {
			notifyErrorListeners("conv2d is not allowed as part of expression", ctx.start);
		}
		
		boolean validBIF = buildForBuiltInFunction(ctx, functionName, paramExpression, f);
		if (validBIF)
			return;

		notifyErrorListeners("only builtin functions allowed as part of expression", ctx.start);
	}


	@Override
	public void exitFunctionCallMultiAssignmentStatement(
			FunctionCallMultiAssignmentStatementContext ctx) {
		String[] names = getQualifiedNames(ctx.name.getText());
		if(names == null) {
			notifyErrorListeners("incorrect function name (only namespace.functionName allowed. Hint: If you are trying to use builtin functions, you can skip the namespace)", ctx.name);
			return;
		}
		String namespace = names[0];
		String functionName = names[1];

		ArrayList<ParameterExpression> paramExpression = getParameterExpressionList(ctx.paramExprs);
		ConvertedDMLSyntax convertedSyntax = convertToDMLSyntax(ctx, namespace, functionName, paramExpression, ctx.name);
		if(convertedSyntax == null) {
			return;
		}
		else {
			namespace = convertedSyntax.namespace;
			functionName = convertedSyntax.functionName;
			paramExpression = convertedSyntax.paramExpression;
		}

		FunctionCallIdentifier functCall = new FunctionCallIdentifier(paramExpression);
		functCall.setFunctionName(functionName);
		functCall.setFunctionNamespace(namespace);


		final ArrayList<DataIdentifier> targetList = new ArrayList<DataIdentifier>();
		for(DataIdentifierContext dataCtx : ctx.targetList) {
			if(dataCtx.dataInfo.expr instanceof DataIdentifier) {
				targetList.add((DataIdentifier) dataCtx.dataInfo.expr);
			}
			else {
				notifyErrorListeners("incorrect type for variable ", dataCtx.start);
				return;
			}
		}

		if(namespace.equals(DMLProgram.DEFAULT_NAMESPACE)) {
			final FunctionCallMultiAssignmentStatementContext fctx = ctx;
			Action f = new Action() {
				@Override public void execute(Expression e) { setMultiAssignmentStatement(targetList, e, fctx, fctx.info); }
			};
			
			boolean validBIF = buildForBuiltInFunction(ctx, functionName, paramExpression, f);
			if (validBIF)
				return;
		}

		setMultiAssignmentStatement(targetList, functCall, ctx, ctx.info);
	}
	
	private ArrayList<ParameterExpression> expandListParams(ArrayList<ParameterExpression> paramExpression, 
			HashSet<String> paramsToExpand, ParserRuleContext ctx) {
		ArrayList<ParameterExpression> newParamExpressions = new ArrayList<ParameterExpression>();
		for(ParameterExpression expr : paramExpression) {
			if(paramsToExpand.contains(expr.getName())) {
				if(expr.getExpr() instanceof ExpressionList) {
					int i = 1;
					for(Expression e : ((ExpressionList)expr.getExpr()).getValue()) {
						newParamExpressions.add(new ParameterExpression(expr.getName() + i, e));
						i++;
					}
				}
			}
			else if(expr.getExpr() instanceof ExpressionList) {
				notifyErrorListeners("the parameter " + expr.getName() + " cannot be list", ctx.start);
			}
			else {
				newParamExpressions.add(expr);
			}
		}
		return newParamExpressions;
	}


	// -----------------------------------------------------------------
	// 			Control Statements - Guards & Loops
	// -----------------------------------------------------------------

	private StatementBlock getStatementBlock(Statement current) {
		return DMLParserWrapper.getStatementBlock(current);
	}

	@Override
	public void exitIfStatement(IfStatementContext ctx) {
		IfStatement ifStmt = new IfStatement();
		ConditionalPredicate predicate = new ConditionalPredicate(ctx.predicate.info.expr);
		ifStmt.setConditionalPredicate(predicate);
		String fileName = currentFile;
		int line = ctx.start.getLine();
		int col = ctx.start.getCharPositionInLine();
		ifStmt.setAllPositions(fileName, line, col, line, col);

		if(ctx.ifBody.size() > 0) {
			for(StatementContext stmtCtx : ctx.ifBody) {
				ifStmt.addStatementBlockIfBody(getStatementBlock(stmtCtx.info.stmt));
			}
			ifStmt.mergeStatementBlocksIfBody();
		}

		if(ctx.elseBody.size() > 0) {
			for(StatementContext stmtCtx : ctx.elseBody) {
				ifStmt.addStatementBlockElseBody(getStatementBlock(stmtCtx.info.stmt));
			}
			ifStmt.mergeStatementBlocksElseBody();
		}

		ctx.info.stmt = ifStmt;
		setFileLineColumn(ctx.info.stmt, ctx);
	}

	@Override
	public void exitWhileStatement(WhileStatementContext ctx) {
		WhileStatement whileStmt = new WhileStatement();
		ConditionalPredicate predicate = new ConditionalPredicate(ctx.predicate.info.expr);
		whileStmt.setPredicate(predicate);
		int line = ctx.start.getLine();
		int col = ctx.start.getCharPositionInLine();
		whileStmt.setAllPositions(currentFile, line, col, line, col);

		if(ctx.body.size() > 0) {
			for(StatementContext stmtCtx : ctx.body) {
				whileStmt.addStatementBlock(getStatementBlock(stmtCtx.info.stmt));
			}
			whileStmt.mergeStatementBlocks();
		}

		ctx.info.stmt = whileStmt;
		setFileLineColumn(ctx.info.stmt, ctx);
	}

	@Override
	public void exitForStatement(ForStatementContext ctx) {
		ForStatement forStmt = new ForStatement();
		int line = ctx.start.getLine();
		int col = ctx.start.getCharPositionInLine();

		DataIdentifier iterVar = new DataIdentifier(ctx.iterVar.getText());
		HashMap<String, String> parForParamValues = null;
		Expression incrementExpr = new IntIdentifier(1, currentFile, line, col, line, col);
		if(ctx.iterPred.info.increment != null) {
			incrementExpr = ctx.iterPred.info.increment;
		}
		IterablePredicate predicate = new IterablePredicate(iterVar, ctx.iterPred.info.from, ctx.iterPred.info.to, incrementExpr, parForParamValues, currentFile, line, col, line, col);
		forStmt.setPredicate(predicate);

		if(ctx.body.size() > 0) {
			for(StatementContext stmtCtx : ctx.body) {
				forStmt.addStatementBlock(getStatementBlock(stmtCtx.info.stmt));
			}
			forStmt.mergeStatementBlocks();
		}
		ctx.info.stmt = forStmt;
		setFileLineColumn(ctx.info.stmt, ctx);
	}

	@Override
	public void exitParForStatement(ParForStatementContext ctx) {
		ParForStatement parForStmt = new ParForStatement();
		int line = ctx.start.getLine();
		int col = ctx.start.getCharPositionInLine();

		DataIdentifier iterVar = new DataIdentifier(ctx.iterVar.getText());
		HashMap<String, String> parForParamValues = new HashMap<String, String>();
		if(ctx.parForParams != null && ctx.parForParams.size() > 0) {
			for(StrictParameterizedExpressionContext parForParamCtx : ctx.parForParams) {
				parForParamValues.put(parForParamCtx.paramName.getText(), parForParamCtx.paramVal.getText());
			}
		}

		Expression incrementExpr = new IntIdentifier(1, currentFile, line, col, line, col);

		if( ctx.iterPred.info.increment != null ) {
			incrementExpr = ctx.iterPred.info.increment;
		}
		IterablePredicate predicate = new IterablePredicate(iterVar, ctx.iterPred.info.from, ctx.iterPred.info.to, incrementExpr, parForParamValues, currentFile, line, col, line, col);
		parForStmt.setPredicate(predicate);
		if(ctx.body.size() > 0) {
			for(StatementContext stmtCtx : ctx.body) {
				parForStmt.addStatementBlock(getStatementBlock(stmtCtx.info.stmt));
			}
			parForStmt.mergeStatementBlocks();
		}
		ctx.info.stmt = parForStmt;
		setFileLineColumn(ctx.info.stmt, ctx);
	}

	private ArrayList<DataIdentifier> getFunctionParameters(List<TypedArgNoAssignContext> ctx) {
		ArrayList<DataIdentifier> retVal = new ArrayList<DataIdentifier>();
		for(TypedArgNoAssignContext paramCtx : ctx) {
			DataIdentifier dataId = new DataIdentifier(paramCtx.paramName.getText());
			String dataType = null;
			String valueType = null;

			if(paramCtx.paramType == null || paramCtx.paramType.dataType() == null
					|| paramCtx.paramType.dataType().getText() == null || paramCtx.paramType.dataType().getText().isEmpty()) {
				dataType = "scalar";
			}
			else {
				dataType = paramCtx.paramType.dataType().getText();
			}

			if(dataType.equals("matrix") || dataType.equals("Matrix")) {
				// matrix
				dataId.setDataType(DataType.MATRIX);
			}
			else if(dataType.equals("scalar") || dataType.equals("Scalar")) {
				// scalar
				dataId.setDataType(DataType.SCALAR);
			}
			else {
				notifyErrorListeners("invalid datatype " + dataType, paramCtx.start);
				return null;
			}

			valueType = paramCtx.paramType.valueType().getText();
			if(valueType.equals("int") || valueType.equals("integer")
				|| valueType.equals("Int") || valueType.equals("Integer")) {
				dataId.setValueType(ValueType.INT);
			}
			else if(valueType.equals("string") || valueType.equals("String")) {
				dataId.setValueType(ValueType.STRING);
			}
			else if(valueType.equals("boolean") || valueType.equals("Boolean")) {
				dataId.setValueType(ValueType.BOOLEAN);
			}
			else if(valueType.equals("double") || valueType.equals("Double")) {
				dataId.setValueType(ValueType.DOUBLE);
			}
			else if(valueType.equals("bool")) {
				notifyErrorListeners("invalid valuetype " + valueType + " (Quickfix: use \'boolean\' instead)", paramCtx.start);
				return null;
			}
			else {
				notifyErrorListeners("invalid valuetype " + valueType, paramCtx.start);
				return null;
			}
			retVal.add(dataId);
		}
		return retVal;
	}

	@Override
	public void exitIterablePredicateColonExpression(IterablePredicateColonExpressionContext ctx) {
		ctx.info.from = ctx.from.info.expr;
		ctx.info.to = ctx.to.info.expr;
		ctx.info.increment = null;
	}

	@Override
	public void exitIterablePredicateSeqExpression(IterablePredicateSeqExpressionContext ctx) {
		if(!ctx.ID().getText().equals("seq")) {
			notifyErrorListeners("incorrect function:\'" + ctx.ID().getText() + "\'. expected \'seq\'", ctx.start);
			return;
		}
		ctx.info.from = ctx.from.info.expr;
		ctx.info.to = ctx.to.info.expr;
		ctx.info.increment = ctx.increment.info.expr;
	}


	// -----------------------------------------------------------------
	// 				Internal & External Functions Definitions
	// -----------------------------------------------------------------

	@Override
	public void exitInternalFunctionDefExpression(InternalFunctionDefExpressionContext ctx) {
		FunctionStatement functionStmt = new FunctionStatement();

		ArrayList<DataIdentifier> functionInputs  = getFunctionParameters(ctx.inputParams);
		functionStmt.setInputParams(functionInputs);

		// set function outputs
		ArrayList<DataIdentifier> functionOutputs = getFunctionParameters(ctx.outputParams);
		functionStmt.setOutputParams(functionOutputs);

		// set function name
		functionStmt.setName(ctx.name.getText());


		if(ctx.body.size() > 0) {
			// handle function body
			// Create arraylist of one statement block
			ArrayList<StatementBlock> body = new ArrayList<StatementBlock>();
			for(StatementContext stmtCtx : ctx.body) {
				body.add(getStatementBlock(stmtCtx.info.stmt));
			}
			functionStmt.setBody(body);
			functionStmt.mergeStatementBlocks();
		}
		else {
			notifyErrorListeners("functions with no statements are not allowed", ctx.start);
			return;
		}

		ctx.info.stmt = functionStmt;
		setFileLineColumn(ctx.info.stmt, ctx);
		ctx.info.functionName = ctx.name.getText();
	}

	@Override
	public void exitExternalFunctionDefExpression(ExternalFunctionDefExpressionContext ctx) {
		ExternalFunctionStatement functionStmt = new ExternalFunctionStatement();

		ArrayList<DataIdentifier> functionInputs  = getFunctionParameters(ctx.inputParams);
		functionStmt.setInputParams(functionInputs);

		// set function outputs
		ArrayList<DataIdentifier> functionOutputs = getFunctionParameters(ctx.outputParams);
		functionStmt.setOutputParams(functionOutputs);

		// set function name
		functionStmt.setName(ctx.name.getText());

		// set other parameters
		HashMap<String, String> otherParams = new HashMap<String,String>();
		boolean atleastOneClassName = false;
		for(StrictParameterizedKeyValueStringContext otherParamCtx : ctx.otherParams){
			String paramName = otherParamCtx.paramName.getText();
			String val = "";
			String text = otherParamCtx.paramVal.getText();
			// First unquote the string
			if(	(text.startsWith("\"") && text.endsWith("\"")) ||
				(text.startsWith("\'") && text.endsWith("\'"))) {
				if(text.length() > 2) {
					val = text.substring(1, text.length()-1);
				}
				// Empty value allowed
			}
			else {
				notifyErrorListeners("the value of user parameter for external function should be of type string", ctx.start);
				return;
			}
			otherParams.put(paramName, val);
			if(paramName.equals("classname")) {
				atleastOneClassName = true;
			}
		}
		functionStmt.setOtherParams(otherParams);
		if(!atleastOneClassName) {
			notifyErrorListeners("the parameter \'className\' needs to be passed for externalFunction", ctx.start);
			return;
		}

		ctx.info.stmt = functionStmt;
		setFileLineColumn(ctx.info.stmt, ctx);
		ctx.info.functionName = ctx.name.getText();
	}


	@Override
	public void exitPathStatement(PathStatementContext ctx) {
		PathStatement stmt = new PathStatement(ctx.pathValue.getText());
		String filePath = ctx.pathValue.getText();
		if((filePath.startsWith("\"") && filePath.endsWith("\"")) ||
				filePath.startsWith("'") && filePath.endsWith("'")) {
			filePath = filePath.substring(1, filePath.length()-1);
		}

		_workingDir = filePath;
		ctx.info.stmt = stmt;
	}

	@Override
	public void exitIfdefAssignmentStatement(IfdefAssignmentStatementContext ctx) {
		if(!ctx.commandLineParam.getText().startsWith("$")) {
			notifyErrorListeners("the first argument of ifdef function should be a commandline argument parameter (which starts with $)", ctx.commandLineParam.start);
			return;
		}

		if(ctx.targetList == null) {
			notifyErrorListeners("ifdef assignment needs an lvalue ", ctx.start);
			return;
		}
		String targetListText = ctx.targetList.getText();
		if(targetListText.startsWith("$")) {
			notifyErrorListeners("lhs of ifdef function cannot be a commandline parameters. Use local variable instead", ctx.start);
			return;
		}

		DataIdentifier target = null;
		if(ctx.targetList.dataInfo.expr instanceof DataIdentifier) {
			target = (DataIdentifier) ctx.targetList.dataInfo.expr;
			Expression source = null;
			if(ctx.commandLineParam.dataInfo.expr != null) {
				// Since commandline parameter is set
				// The check of following is done in fillExpressionInfoCommandLineParameters:
				// Command line param cannot be empty string
				// If you want to pass space, please quote it
				source = ctx.commandLineParam.dataInfo.expr;
			}
			else {
				source = ctx.source.info.expr;
			}

			int line = ctx.start.getLine();
			int col = ctx.start.getCharPositionInLine();
			try {
				ctx.info.stmt = new AssignmentStatement(target, source, line, col, line, col);
				setFileLineColumn(ctx.info.stmt, ctx);
			} catch (LanguageException e) {
				notifyErrorListeners("invalid assignment for ifdef function", ctx.targetList.start);
				return;
			}

		}
		else {
			notifyErrorListeners("incorrect lvalue in ifdef function ", ctx.targetList.start);
			return;
		}
	}

	@Override
	public void exitMatrixDataTypeCheck(MatrixDataTypeCheckContext ctx) {
		boolean validMatrixType = ctx.ID().getText().equals("matrix")
								|| ctx.ID().getText().equals("Matrix")
								|| ctx.ID().getText().equals("Scalar")
								|| ctx.ID().getText().equals("scalar");
		if(!validMatrixType	) {
			notifyErrorListeners("incorrect datatype (expected matrix or scalar)", ctx.start);
		}
	}


	// -----------------------------------------------------------------
	// 			Not overridden
	// -----------------------------------------------------------------

	@Override public void visitTerminal(TerminalNode node) {}

	@Override public void visitErrorNode(ErrorNode node) {}

	@Override public void exitEveryRule(ParserRuleContext ctx) {}

	@Override public void enterModIntDivExpression(ModIntDivExpressionContext ctx) {}

	@Override public void enterExternalFunctionDefExpression(ExternalFunctionDefExpressionContext ctx) {}

	@Override public void enterBooleanNotExpression(BooleanNotExpressionContext ctx) {}

	@Override public void enterPowerExpression(PowerExpressionContext ctx) {}

	@Override public void enterInternalFunctionDefExpression(InternalFunctionDefExpressionContext ctx) {}

	@Override public void enterBuiltinFunctionExpression(BuiltinFunctionExpressionContext ctx) {}

	@Override public void enterConstIntIdExpression(ConstIntIdExpressionContext ctx) {}

	@Override public void enterAtomicExpression(AtomicExpressionContext ctx) {}

	@Override public void enterIfdefAssignmentStatement(IfdefAssignmentStatementContext ctx) {}

	@Override public void enterConstStringIdExpression(ConstStringIdExpressionContext ctx) {}

	@Override public void enterConstTrueExpression(ConstTrueExpressionContext ctx) {}

	@Override public void enterParForStatement(ParForStatementContext ctx) {}

	@Override public void enterUnaryExpression(UnaryExpressionContext ctx) {}

	@Override public void enterImportStatement(ImportStatementContext ctx) {}

	@Override public void enterPathStatement(PathStatementContext ctx) {}

	@Override public void enterWhileStatement(WhileStatementContext ctx) {}

	@Override public void enterCommandlineParamExpression(CommandlineParamExpressionContext ctx) {}

	@Override public void enterFunctionCallAssignmentStatement(FunctionCallAssignmentStatementContext ctx) {}

	@Override public void enterAddSubExpression(AddSubExpressionContext ctx) {}

	@Override public void enterIfStatement(IfStatementContext ctx) {}

	@Override public void enterConstDoubleIdExpression(ConstDoubleIdExpressionContext ctx) {}

	@Override public void enterMatrixMulExpression(MatrixMulExpressionContext ctx) {}

	@Override public void enterMatrixDataTypeCheck(MatrixDataTypeCheckContext ctx) {}

	@Override public void enterCommandlinePositionExpression(CommandlinePositionExpressionContext ctx) {}

	@Override public void enterIterablePredicateColonExpression(IterablePredicateColonExpressionContext ctx) {}

	@Override public void enterAssignmentStatement(AssignmentStatementContext ctx) {}

	@Override public void enterValueType(ValueTypeContext ctx) {}

	@Override public void exitValueType(ValueTypeContext ctx) {}

	@Override public void enterMl_type(Ml_typeContext ctx) {}

	@Override public void exitMl_type(Ml_typeContext ctx) {}

	@Override public void enterBooleanAndExpression(BooleanAndExpressionContext ctx) {}

	@Override public void enterForStatement(ForStatementContext ctx) {}

	@Override public void enterRelationalExpression(RelationalExpressionContext ctx) {}

	@Override public void enterTypedArgNoAssign(TypedArgNoAssignContext ctx) {}

	@Override public void exitTypedArgNoAssign(TypedArgNoAssignContext ctx) {}

	@Override public void enterStrictParameterizedExpression(StrictParameterizedExpressionContext ctx) {}

	@Override public void exitStrictParameterizedExpression(StrictParameterizedExpressionContext ctx) {}

	@Override public void enterMultDivExpression(MultDivExpressionContext ctx) {}

	@Override public void enterConstFalseExpression(ConstFalseExpressionContext ctx) {}

	@Override public void enterStrictParameterizedKeyValueString(StrictParameterizedKeyValueStringContext ctx) {}

	@Override public void exitStrictParameterizedKeyValueString(StrictParameterizedKeyValueStringContext ctx) {}

	@Override public void enterProgramroot(ProgramrootContext ctx) {}

	@Override public void exitProgramroot(ProgramrootContext ctx) {}

	@Override public void enterDataIdExpression(DataIdExpressionContext ctx) {}

	@Override public void enterIndexedExpression(IndexedExpressionContext ctx) {}

	@Override public void enterParameterizedExpression(ParameterizedExpressionContext ctx) {}

	@Override public void exitParameterizedExpression(ParameterizedExpressionContext ctx) {}

	@Override public void enterFunctionCallMultiAssignmentStatement(FunctionCallMultiAssignmentStatementContext ctx) {}

	@Override public void enterIterablePredicateSeqExpression(IterablePredicateSeqExpressionContext ctx) {}

	@Override public void enterSimpleDataIdentifierExpression(SimpleDataIdentifierExpressionContext ctx) {}

	@Override public void enterBooleanOrExpression(BooleanOrExpressionContext ctx) {}

	@Override
	public void enterMultiIdExpression(MultiIdExpressionContext ctx) { }
	
	@Override
	public void enterOptionalUpperIndexExpression(OptionalUpperIndexExpressionContext ctx) { }

	@Override
	public void exitOptionalUpperIndexExpression(OptionalUpperIndexExpressionContext ctx) { }

	@Override
	public void exitMultiIdExpression(MultiIdExpressionContext ctx) {
		ArrayList<Expression> values = new ArrayList<Expression>();
		for(ExpressionContext elem : ctx.targetList) {
			values.add(elem.info.expr);
		}
		ctx.info.expr = new ExpressionList(values);
	}

	@Override
	public void enterTensorIndexedExpression(TensorIndexedExpressionContext ctx) { }

	private boolean isLowerEmpty(OptionalUpperIndexExpressionContext index) {
		if(index != null && index.lowerIndex != null && !index.lowerIndex.isEmpty()) {
			return false;
		}
		return true;
	}
	
	private boolean isUpperEmpty(OptionalUpperIndexExpressionContext index) {
		if(index != null && index.upperIndex != null && !index.upperIndex.isEmpty()) {
			return false;
		}
		return true;
	}
	
	private IntIdentifier createIntIdentifier(long val, ParserRuleContext ctx) {
		IntIdentifier tmp = new IntIdentifier(val, currentFile, ctx.start.getLine(),
				ctx.start.getCharPositionInLine(), ctx.stop.getLine(), ctx.stop.getCharPositionInLine());
		return tmp;
	}
	
	private BinaryExpression createBinaryExpression(BinaryOp bop, ParserRuleContext ctx,
			Expression left, Expression right) {
		BinaryExpression tmp = new BinaryExpression(bop, currentFile, ctx.start.getLine(),
				ctx.start.getCharPositionInLine(), ctx.stop.getLine(), ctx.stop.getCharPositionInLine());
		tmp.setLeft(left);
		tmp.setRight(right);
		return tmp;
	}
	
	// Special case when indexing only using first dimension
	private Expression getMatrixRowIndex(Expression tensorFirstDimRowIndex, ArrayList<Expression> shape, ParserRuleContext ctx, boolean isLower) {
		Expression expr = null;
		if(isLower) {
			expr = createBinaryExpression(BinaryOp.MINUS, ctx, tensorFirstDimRowIndex, createIntIdentifier(1, ctx));
			for(int i = 1; i < shape.size()-1; i++) {
				expr = createBinaryExpression(BinaryOp.MULT, ctx, expr, shape.get(i));
			}
			expr = createBinaryExpression(BinaryOp.PLUS, ctx, expr, createIntIdentifier(1, ctx));
		}
		else {
			expr = tensorFirstDimRowIndex;
			for(int i = 1; i < shape.size()-1; i++) {
				expr = createBinaryExpression(BinaryOp.MULT, ctx, expr, shape.get(i));
			}
		}
		return expr;
	}

	@Override
	public void exitTensorIndexedExpression(TensorIndexedExpressionContext ctx) {
		if(!ctx.shapeName.getText().equals("shape")) {
			notifyErrorListeners("incorrect parameter \"" + ctx.shapeName.getText() + "\" in tensor indexing", ctx.shapeName);
			return;
		}
		ArrayList<Expression> shape = new ArrayList<Expression>();
		for(ExpressionContext dim : ctx.dimensions) {
			shape.add(dim.info.expr);
		}
		
		ExpressionInfo rowLower = null;
		ExpressionInfo rowUpper = null;
		ExpressionInfo colLower = null;
		ExpressionInfo colUpper = null;
		boolean isSupported = false;
		
		// ------------------------------------------------------------
		
		// Special case: Indexing on only first two dimensions
		OptionalUpperIndexExpressionContext firstIndex = (ctx.indexes != null && ctx.indexes.size() >= 1) ? ctx.indexes.get(0) : null;
		OptionalUpperIndexExpressionContext secondIndex = (ctx.indexes != null && ctx.indexes.size() >= 2) ? ctx.indexes.get(1) : null;
		
		boolean isFirstLowerEmpty = firstIndex == null || isLowerEmpty(firstIndex);
		boolean isFirstUpperEmpty = firstIndex == null || isUpperEmpty(firstIndex);
		boolean isSecondLowerEmpty = secondIndex == null || isLowerEmpty(secondIndex);
		boolean isSecondUpperEmpty = secondIndex == null || isUpperEmpty(secondIndex);
		
		if(!isFirstLowerEmpty || !isFirstUpperEmpty || !isSecondLowerEmpty || !isSecondUpperEmpty) {
			isSupported = true;
			for(int i = 2; i < ctx.indexes.size(); i++) {
				OptionalUpperIndexExpressionContext otherIndex = ctx.indexes.get(i);
				if(!isLowerEmpty(otherIndex) && !isUpperEmpty(otherIndex)) {
					isSupported = false;
					break;
				}
			}
			
			if(isSupported) {
				if(!isFirstLowerEmpty) {
					rowLower = new ExpressionInfo();
					rowLower.expr = firstIndex.lowerIndex.info.expr;
				}
				if(!isFirstUpperEmpty) {
					rowUpper = new ExpressionInfo();
					rowUpper.expr = firstIndex.upperIndex.info.expr;
				}
				if(!isSecondLowerEmpty) {
					colLower = new ExpressionInfo();
					// TODO:
					notifyErrorListeners("the given tensor indexing is not supported yet", ctx.start);
					return;
				}
				if(!isSecondUpperEmpty) {
					colUpper = new ExpressionInfo();
					// TODO:
					notifyErrorListeners("the given tensor indexing is not supported yet", ctx.start);
					return;
				}
			}
		}
		
		// ------------------------------------------------------------
		if(!isSupported) {
			notifyErrorListeners("the given tensor indexing is not supported", ctx.start);
			return;
		}
		
		boolean isRowLower = (rowLower != null);
		boolean isRowUpper = (rowUpper != null);
		boolean isColLower = (colLower != null);
		boolean isColUpper = (colUpper != null);
		String name = ctx.name.getText();
		
		ctx.dataInfo.expr = new IndexedIdentifier(name, false, false);
		
		exitIndexedExpressionHelper(ctx, name, ctx.dataInfo,
				isRowLower ? rowLower : null,
				isRowUpper ? rowUpper : null,
				isColLower ? colLower : null,
				isColUpper ? colUpper : null);
	}	

}
