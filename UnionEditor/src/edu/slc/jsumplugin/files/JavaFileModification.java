package edu.slc.jsumplugin.files;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.misc.Pair;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.*;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;

import ast.Ast.Traversal;
import ast.Ast.Variant;
import ast.CompareAst.CompareUnions;
import ast.Type.*;

public class JavaFileModification {
	
	/*
	 * main function & method------------------------------------------------------------------------------------- 
	 */	

	public static void modifyInstance(ICompilationUnit iUnit, Traversal t, CompareUnions compareUnions) throws 
	JavaModelException, IllegalArgumentException, MalformedTreeException, org.eclipse.jface.text.BadLocationException {
		// insert
		for (Variant v : compareUnions.getTraversalInstaces(t, 0)) {
			CompilationUnit astRoot = parse(iUnit);
			// get text edits
			Pair<ast.Type, String> traversal_union_type = compareUnions.getUnionTypeInTraversal(t);
			TextEdit edits = insertInstance(astRoot, t, v, traversal_union_type);
			// apply the text edits to the compilation unit
			Document document = new Document(iUnit.getSource());
			edits.apply(document);
			// adding statement to the buffer iUnit will be created in the addInstance in the JavaSystem
			iUnit.getBuffer().setContents(document.get());
		}
		// delete
		for (Variant v : compareUnions.getTraversalInstaces(t, 1)) {
			CompilationUnit astRoot = parse(iUnit);
			// get text edits
			Pair<ast.Type, String> traversal_union_type = compareUnions.getUnionTypeInTraversal(t);
			TextEdit edits = removeInstance(astRoot, t, v, traversal_union_type);
			// apply the text edits to the compilation unit
			Document document = new Document(iUnit.getSource());
			edits.apply(document);
			// adding statement to the buffer iUnit will be created in the addInstance in the JavaSystem
			iUnit.getBuffer().setContents(document.get());
		}

	}
	
	// check the condition expression of the instanceOf chain
	public static void checkConditionExpression(ICompilationUnit iUnit) throws JavaModelException {
		
		List<Type> types = new ArrayList<Type>();
		//loop through all statements in the method
		for (Object o : getFirstMethod(iUnit).getBody().statements()) {
			Statement s = (Statement) o;
			while (s instanceof IfStatement) {
				IfStatement is = (IfStatement) s;
				if (is.getExpression() instanceof InstanceofExpression) {
					InstanceofExpression instanceOfExp = (InstanceofExpression) is.getExpression();
					types.add(instanceOfExp.getRightOperand());
					if (instanceOfExp.getLeftOperand() instanceof Name) {
						Name leftExp = (Name) instanceOfExp.getLeftOperand();
						if (leftExp.resolveBinding() instanceof IVariableBinding) {
							IVariableBinding leftVariableBinding = (IVariableBinding) leftExp.resolveBinding();
							System.out.printf("left expression type: %s, right type: %s. \n", leftVariableBinding.getType().getName(), instanceOfExp.getRightOperand().resolveBinding().getName());
							//System.out.println(leftVariableBinding.getType().isCastCompatible(instanceOfExp.getRightOperand().resolveBinding()));
						}
					}
				}
				s = is.getElseStatement();
			}
		}
		
		ITypeBinding superType = null;
		for (Type t : types) {
			if (superType != null){
				System.out.println(t.resolveBinding().getSuperclass().isEqualTo(superType));
			}
			superType = t.resolveBinding().getSuperclass();
		}
	}
	
	
	/*
	 * helper method------------------------------------------------------------------------------------- 
	 */
	
	private static TextEdit removeInstance(CompilationUnit astRoot, Traversal t, Variant v, Pair<ast.Type, String> traversal_union_type) throws JavaModelException, IllegalArgumentException {
		// create a ASTRewrite
		AST ast = astRoot.getAST();
		ASTRewrite rewriter = ASTRewrite.create(ast);
		// for getting insertion position (first method)
		TypeDeclaration typeDecl = (TypeDeclaration) astRoot.types().get(0);
		MethodDeclaration methodDecl = typeDecl.getMethods()[0];
		Block block = methodDecl.getBody();// add to this block
		
		// set up new if statement
		IfStatement newIfStatement = ast.newIfStatement();
		Statement newElses = null;
		boolean first = true;
		
		// loop through all statements in this block
		for (Object o : block.statements()) {
			Statement s = (Statement) o;
			while (s instanceof IfStatement) {
				IfStatement is = (IfStatement) s;
				if (is.getExpression() instanceof InstanceofExpression) {
					InstanceofExpression instanceOfExp = (InstanceofExpression) is.getExpression();		
					if (instanceOfExp.getLeftOperand() instanceof Name) {
						Name leftExp = (Name) instanceOfExp.getLeftOperand();
						if (leftExp.resolveBinding() instanceof IVariableBinding) {
							
							// check if cases match traversal and the variant
							String left_name = leftExp.getFullyQualifiedName();
							String right_TypeName = instanceOfExp.getRightOperand().toString();
							if (left_name.equals(traversal_union_type.b) && right_TypeName.equals(v.getName())) {
								if (first) {
									Statement rest = (Statement) is.getElseStatement().copySubtree(ast, is.getElseStatement());
									if (rest instanceof Block) {
										newIfStatement = (IfStatement) ((Block) rest).statements().get(0);
									} else {
										newIfStatement = (IfStatement) rest;
									}
									first = false;
									
								} else {
									System.out.println(newIfStatement);
									Statement rest = (Statement) is.getElseStatement().copySubtree(ast, is.getElseStatement());
									if (newElses == null) {
										newIfStatement.setElseStatement(rest);
									} else {
										getEmptyElse((IfStatement) newElses).setElseStatement(rest);
										newIfStatement.setElseStatement((Statement) newElses.copySubtree(ast, newElses));
									}
									
								}
								// create ListRewrite
								ListRewrite listRewrite = rewriter.getListRewrite(block, Block.STATEMENTS_PROPERTY);
								listRewrite.replace((ASTNode) block.statements().get(0), newIfStatement, null);
								TextEdit edits = rewriter.rewriteAST();
								return edits;
							} else {
								if (first) {
									newIfStatement.setExpression((Expression) instanceOfExp.copySubtree(ast, instanceOfExp));
									newIfStatement.setThenStatement((Statement) is.getThenStatement().copySubtree(ast, is.getThenStatement()));
									first = false;
								} else {
									IfStatement keepVariant = ast.newIfStatement();
									keepVariant.setExpression((Expression) instanceOfExp.copySubtree(ast, instanceOfExp));
									keepVariant.setThenStatement((Statement) is.getThenStatement().copySubtree(ast, is.getThenStatement()));
									if (newElses == null) {
										newElses = keepVariant;
									} else {
										getEmptyElse((IfStatement) newElses).setElseStatement(keepVariant);
									}
									newIfStatement.setElseStatement((Statement) newElses.copySubtree(ast, newElses));
								}
							}
						}
					}
				}
				s = is.getElseStatement();
			}
		}
		return null;
	}



	private static IfStatement getEmptyElse(IfStatement newElses) {
		if (newElses.getElseStatement() == null) {
			return newElses;
		} else {
			return getEmptyElse((IfStatement) newElses.getElseStatement());
		}
	}

	private static TextEdit insertInstance(CompilationUnit astRoot, Traversal t, Variant v, Pair<ast.Type, String> traversal_union_type) 
			throws MalformedTreeException, org.eclipse.jface.text.BadLocationException, JavaModelException, IllegalArgumentException {
		// create a ASTRewrite
		AST ast = astRoot.getAST();
		ASTRewrite rewriter = ASTRewrite.create(ast);
		// for getting insertion position (first method)
		TypeDeclaration typeDecl = (TypeDeclaration) astRoot.types().get(0);
		MethodDeclaration methodDecl = typeDecl.getMethods()[0];
		Block block = methodDecl.getBody();// add to this block
		// create new conditionalExp
		Type rightType = ast.newSimpleType(ast.newName(v.getName()));		
		Name leftExp = ast.newName(traversal_union_type.b.toString());
		InstanceofExpression conditionExp = ast.newInstanceofExpression();
		conditionExp.setLeftOperand(leftExp);
		conditionExp.setRightOperand(rightType);
		// create new if statement
		IfStatement newIfStatement = ast.newIfStatement();
		newIfStatement.setExpression(conditionExp);
		//set then statement
		Block then = ast.newBlock();
		if (t.getReturn_type().toString().equals("void")) {} 
		else if (t.getReturn_type() instanceof NumericType) {
			ReturnStatement returnStmt = ast.newReturnStatement();
			returnStmt.setExpression(ast.newNumberLiteral("0"));
			then.statements().add(returnStmt);
			newIfStatement.setThenStatement(then);
		} else if (t.getReturn_type() instanceof BooleanType) {
			ReturnStatement returnStmt = ast.newReturnStatement();
			returnStmt.setExpression(ast.newBooleanLiteral(false));
			then.statements().add(returnStmt);
			newIfStatement.setThenStatement(then);
		} else {
			ReturnStatement returnStmt = ast.newReturnStatement();
			returnStmt.setExpression(ast.newNullLiteral());
			then.statements().add(returnStmt);
			newIfStatement.setThenStatement(then);
		}
		ListRewrite stubComment = rewriter.getListRewrite(then, Block.STATEMENTS_PROPERTY);
		Statement placeHolder = (Statement) rewriter.createStringPlaceholder("// TODO Auto-generated case match pattern", ASTNode.BLOCK);
		stubComment.insertFirst(placeHolder, null);
		//set else
		Statement elseIfs = (Statement) ASTNode.copySubtree(ast, (ASTNode) block.statements().get(0));
		newIfStatement.setElseStatement(elseIfs);
		// create ListRewrite
		ListRewrite listRewrite = rewriter.getListRewrite(block, Block.STATEMENTS_PROPERTY);
		// listRewrite.insertFirst(newIfStatement, null);
		listRewrite.replace((ASTNode) block.statements().get(0), newIfStatement, null);
		TextEdit edits = rewriter.rewriteAST();
		return edits;
	}
	
	
	private static MethodDeclaration getFirstMethod(ICompilationUnit iUnit) throws JavaModelException {
		// parse compilation unit
		CompilationUnit astRoot = parse(iUnit);
		// choose type and method
		TypeDeclaration typeDecl = (TypeDeclaration) astRoot.types().get(0);
		MethodDeclaration methodDecl = typeDecl.getMethods()[0];
		return methodDecl;
	}
	
	private static CompilationUnit parse(ICompilationUnit unit) {
		ASTParser parser = ASTParser.newParser(AST.JLS8);
		parser.setKind(ASTParser.K_COMPILATION_UNIT);
		parser.setSource(unit);
		parser.setResolveBindings(true);
		return (CompilationUnit) parser.createAST(null);
	}


}
