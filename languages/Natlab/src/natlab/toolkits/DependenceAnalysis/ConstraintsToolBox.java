package natlab.toolkits.DependenceAnalysis;

import java.io.File;
import java.io.RandomAccessFile;

import natlab.ast.AssignStmt;
import natlab.ast.Expr;
import natlab.ast.ForStmt;
import natlab.ast.IntLiteralExpr;
import natlab.ast.MinusExpr;
import natlab.ast.NameExpr;
import natlab.ast.ParameterizedExpr;
import natlab.ast.PlusExpr;
import natlab.ast.RangeExpr;
import natlab.ast.Stmt;

/*
 * Author:Amina Aslam
 * Date:06 Jul,2009
 * ConstraintsToolBox class creates constraints from the equations and puts them in ConstraintsGraph.
 */


/*
 * TO DO:Make the Constraints and add them to ConstraintsGraph.
 */

public class ConstraintsToolBox {
	
	private ForStmt forNode;		
	private String dependencyFlag="No";
	private ForStmt forStmtArray[]=new ForStmt[3];
	private static int loopIndex=0;
	private static boolean resultArray[];
	private ConstraintsGraph cGraph;
	
	public ConstraintsToolBox(ForStmt fNode)
	{
		forNode=fNode;
		forStmtArray[loopIndex]=fNode;
		cGraph=new ConstraintsGraph();
		//checkArrayAccessAcrossStmts();

		
	}
	/*
	 * This function does the following
	 * 1.Calls checkArrayAccessAcrossStmts() and returns ConstraintsGraph based on the Constraints in the system of equations.
	 */
	public ConstraintsGraph createContraints()
	{
		checkArrayAccessAcrossStmts();
		return cGraph;
		
	}

	/*
	 * This function does the following 
	 * 1.Checks for tightly nested loops.
	 */	
		private void isTightlyNestedLoop(ForStmt forStmt)
		{
			 
			  Stmt stmt=forStmt.getStmt(0);
			  if(stmt instanceof ForStmt)
			  {			  
				  loopIndex++;
				  ForStmt tForStmt=(ForStmt)stmt;
				  forStmtArray[loopIndex]=tForStmt;
				  forNode=tForStmt;			  
				  //AssignStmt aStmt=(AssignStmt)forNode.getAssignStmt();
				  //System.out.println("Here is the structure string"+aStmt.dumpTree());
				  isTightlyNestedLoop(tForStmt);				  
			  }
			
		}
		
	/*This function checks whether accessed arrays are the same or not.
	 * e.g. for int i=1:1:10
	 *  		a(i,j)=a(i+10,j-9)
	 *  	end
	 */	
		private void checkSameArrayAccess(Expr aExpr,Expr bExpr)
		{		 
			    	  if(aExpr instanceof ParameterizedExpr)
						  { 			  
							  if(bExpr instanceof ParameterizedExpr)
							  {					  
								  if(aExpr.getVarName().equals(bExpr.getVarName()))
								  {
									  System.out.println("I am in parameterized expression");								  
									  makeEquationsForSubscriptExprs(aExpr,bExpr);
									  //reportTestResult(((ParameterizedExpr)aExpr).getNumArg());								  
								  }
						      }				 
							  else if (bExpr instanceof PlusExpr)
							  {
								  PlusExpr pExpr=(PlusExpr)bExpr;					  
								    if(aExpr.getVarName().equals(pExpr.getLHS().getVarName()))
								    	{
								    		  System.out.println("I am in plus expression for LHS");
								    	      makeEquationsForSubscriptExprs(aExpr,pExpr.getLHS());				    		  
								    		  //reportTestResult(((ParameterizedExpr)aExpr).getNumArg());							    		  
								    	}						    
								    if(aExpr.getVarName().equals(pExpr.getRHS().getVarName()))
								    	{
								    	  System.out.println("I am in plus expression RHS");
								    	  makeEquationsForSubscriptExprs(aExpr,pExpr.getRHS());
							    		  //reportTestResult(((ParameterizedExpr)aExpr).getNumArg());					    	
								        }					
							  }//end of PlusExpr else if
							    
						  }//end of ParameterizedExpr if 		  
						
			}//end of function checkSameArrayAccess.
		
		
		
		
		/*This function checks array accesses across loop statements.
		 * e.g. 1:a(i)=b(i)+c(i)
		 * 		2:d(i)=a(i)
		 */	
		private void checkArrayAccessAcrossStmts()
		{		
			isTightlyNestedLoop(forNode);				
			int nStmts=forNode.getNumStmt();
			AssignStmt aStmt1=null;
			for(int i=0;i<nStmts;i++)
			{
			  Stmt stmt=forNode.getStmt(i);
			  if(stmt instanceof AssignStmt)
			  {
				  aStmt1=(AssignStmt)stmt;
			  }//end of if
			  for(int j=i;j<nStmts;j++)
			  {			  
				  if(i==j)
				  {
					  System.out.println("i am in same statements block");
					  checkSameArrayAccess(aStmt1.getLHS(),aStmt1.getRHS());//compare LHS(sameStmt) with RHS(sameStmt)
					  //writeResults(aStmt1,aStmt1,dependencyFlag);
				  }//end of if
				  else
				  {
					  Stmt bStmt=forNode.getStmt(j);
					  if(bStmt instanceof AssignStmt)
					  {
						  System.out.println("i am in different statements block");
						  AssignStmt aStmt2=(AssignStmt)bStmt;
						  checkSameArrayAccess(aStmt1.getLHS(),aStmt2.getRHS()); //compare LHS(previousStmt) with RHS(nextStmt)
						  checkSameArrayAccess(aStmt1.getLHS(),aStmt2.getLHS());//compare LHS(previousStmt) with LHS(nextStmt)
						  checkSameArrayAccess(aStmt1.getRHS(),aStmt2.getLHS());//compare RHS(previousStmt) with LHS(nextStmt)
						  //writeResults(aStmt1,aStmt2,dependencyFlag);
					  }//end of if
				  }//end of else
			  }//end of inner for loop
			}//end of main for loop	  
						
		}//end of function checkArrayAccessAcrossStmts.	
		
		
		
		/*This function does following. 
		 * 1.Makes equations from array subscript expression.
		 * 2.Call checkDependence function .
		 * TO DO:Handle cases where LHS is not an instance of NameExpr.
		 * 		 	
		 * 
		 */
		private void makeEquationsForSubscriptExprs(Expr LHSExpr,Expr RHSExpr)
		{
			
			ParameterizedExpr paraLHSExpr=(ParameterizedExpr)LHSExpr;
			AffineExpression aExpr1=new AffineExpression();
			AffineExpression aExpr2=new AffineExpression();		
			
			resultArray=new boolean[paraLHSExpr.getNumArg()];   //instantiate a boolean array based on dimensions of array under dependence testing.	
			//if(RHSExpr instanceof ParameterizedExpr)			//{
								
				 for(int i=0;i <paraLHSExpr.getNumArg();i++)   // To handle multi dimensional arrays. e.g.a(i,j)=a(j-11,i+10)
				  {				  
					 if(paraLHSExpr.getArg(i) instanceof NameExpr && ((ParameterizedExpr)RHSExpr).getArg(i) instanceof PlusExpr)
					 {
						 NameExpr nExpr=(NameExpr)paraLHSExpr.getArg(i);
						 aExpr1.setLoopVariable(nExpr.getVarName());
						 aExpr2.setLoopVariable(nExpr.getVarName());
						 aExpr1.setC(0);
						 aExpr1.setVariable("t"+i);			
						 PlusExpr pExpr=(PlusExpr)((ParameterizedExpr)RHSExpr).getArg(i);
						 aExpr2.setVariable("t"+i);
						 setUpperAndLowerBounds(aExpr1,aExpr2);
						 if(pExpr.getRHS() instanceof IntLiteralExpr)			
						 {
							 IntLiteralExpr iExpr=(IntLiteralExpr)pExpr.getRHS();				
							 aExpr2.setC((iExpr.getValue().getValue().intValue())*-1);
							 //checkDependence(aExpr1,aExpr2,i);
							 cGraph.createGraph(aExpr1,aExpr2);
						 }//end of nested if	
					 }//end of main if			
					 else if(paraLHSExpr.getArg(i) instanceof NameExpr && ((ParameterizedExpr)RHSExpr).getArg(i) instanceof MinusExpr)
					 {
						 NameExpr nExpr=(NameExpr)paraLHSExpr.getArg(i);
						 aExpr1.setLoopVariable(nExpr.getVarName());
						 aExpr2.setLoopVariable(nExpr.getVarName());
						 aExpr1.setC(0);
						 aExpr1.setVariable("t"+i);			
						 MinusExpr mExpr=(MinusExpr)((ParameterizedExpr)RHSExpr).getArg(i);
						 aExpr2.setVariable("t"+i);
						 setUpperAndLowerBounds(aExpr1,aExpr2);
						 if(mExpr.getRHS() instanceof IntLiteralExpr)			
						 {
							 IntLiteralExpr iExpr=(IntLiteralExpr)mExpr.getRHS();				
							 aExpr2.setC(iExpr.getValue().getValue().intValue());					
							 //checkDependence(aExpr1,aExpr2,i);
							 cGraph.createGraph(aExpr1,aExpr2);
						 }//end of nested if	
				     }//end of main else if
					 else if(paraLHSExpr.getArg(i) instanceof NameExpr && ((ParameterizedExpr)RHSExpr).getArg(i) instanceof NameExpr)
					 {
						 NameExpr nExpr=(NameExpr)paraLHSExpr.getArg(i);
						 //NameExpr nExpr2=(NameExpr)((ParameterizedExpr)RHSExpr).getArg(i);
						 aExpr1.setLoopVariable(nExpr.getVarName());
						 aExpr2.setLoopVariable(nExpr.getVarName());
						 aExpr1.setC(0);
						 aExpr1.setVariable("t"+i);
						 aExpr2.setC(0);
						 aExpr2.setVariable("t"+i);
						 setUpperAndLowerBounds(aExpr1, aExpr2);
						 cGraph.createGraph(aExpr1,aExpr2);
						 //checkDependence(aExpr1,aExpr2,i);
				     }//end of main else if				
			}//end of for
		//}//end of main if			
	}//end of function makeEquationsForSubscriptExprs
		
									
		/*
		 * This function sets the upper and lower bounds of the affine expression based on the loop which bounds those expressions.
		 * e.g. for int i=1:1:10
		 * 			for int j=1:1:20
		 *  			a(i,j)=a(j-10,i+11)
		 *  		end
		 *  	end
		 *  Affine expressions i, j-10 would be bound by i loop
		 */							
		private void setUpperAndLowerBounds(AffineExpression aExpr1 , AffineExpression aExpr2)
		{	
		    int upperBound=0;
		    int lowerBound=0;
		    
			for(int i=0;i<=loopIndex;i++)
			 {
				  AssignStmt assStmt= forStmtArray[i].getAssignStmt();					
			 	  if(assStmt.getVarName().equals(aExpr1.getLoopVariable())) //this is to compare array subscript with loop index.
			 		{
			 		  if(assStmt.getRHS() instanceof RangeExpr)		 		
						{
			 			  RangeExpr rExpr=(RangeExpr) assStmt.getRHS();						
						  if(rExpr.getUpper() instanceof IntLiteralExpr && rExpr.getLower() instanceof IntLiteralExpr)
						   {
							    System.out.println("I am a constraint not bounded on both sides by variables of IntLiteralExpression");
			 				    IntLiteralExpr iExprUpper=(IntLiteralExpr) rExpr.getUpper();
								//upperBound=iExprUpper.getValue().getValue().intValue();
								IntLiteralExpr iExprLower=(IntLiteralExpr) rExpr.getLower();
								//lowerBound=iExprLower.getValue().getValue().intValue();
								aExpr1.setUpperBound(iExprUpper);
								aExpr1.setLowerBound(iExprLower);
								aExpr2.setUpperBound(iExprUpper);
								aExpr2.setLowerBound(iExprLower);
						   }//end of 3rd if
						  else if(rExpr.getUpper() instanceof PlusExpr)
							{
								System.out.println("I am a constraint bounded on both sides by variables by PlusExpr");
								PlusExpr pExpr=(PlusExpr)rExpr.getUpper();
								aExpr1.setPUBound(pExpr);
								aExpr2.setPUBound(pExpr);
								if(rExpr.getLower() instanceof NameExpr)
								{
									NameExpr nExpr=(NameExpr)rExpr.getLower();
									aExpr1.setNLBound(nExpr);
									aExpr2.setNLBound(nExpr);
								}
								else if(rExpr.getLower() instanceof IntLiteralExpr)
								{
									IntLiteralExpr iExprLower=(IntLiteralExpr) rExpr.getLower();
									//lowerBound=iExprLower.getValue().getValue().intValue();
									aExpr1.setLowerBound(iExprLower);									
									aExpr2.setLowerBound(iExprLower);
								}
								else if(rExpr.getLower() instanceof PlusExpr)
								{
									PlusExpr pLExpr=(PlusExpr)rExpr.getLower();
									aExpr1.setPLBound(pLExpr);
									aExpr2.setPLBound(pLExpr);									
								}	
								else if(rExpr.getLower() instanceof MinusExpr)
								{
									MinusExpr mLExpr=(MinusExpr)rExpr.getLower();
									aExpr1.setMLBound(mLExpr);
									aExpr2.setMLBound(mLExpr);
								}								
							}//end of else if
						  else if(rExpr.getUpper() instanceof MinusExpr)
							{
								System.out.println("I am a constraint bounded on both sides by variables");
								MinusExpr mExpr=(MinusExpr)rExpr.getUpper();
								aExpr1.setMUBound(mExpr);
								aExpr2.setMUBound(mExpr);
								if(rExpr.getLower() instanceof NameExpr)
								{
									NameExpr nExpr=(NameExpr)rExpr.getLower();
									aExpr1.setNLBound(nExpr);
									aExpr2.setNLBound(nExpr);
								}
								else if(rExpr.getLower() instanceof IntLiteralExpr)
								{
									IntLiteralExpr iExprLower=(IntLiteralExpr) rExpr.getLower();
									//lowerBound=iExprLower.getValue().getValue().intValue();
									aExpr1.setLowerBound(iExprLower);									
									aExpr2.setLowerBound(iExprLower);
								}
								else if(rExpr.getLower() instanceof PlusExpr)
								{
									PlusExpr pLExpr=(PlusExpr)rExpr.getLower();
									aExpr1.setPLBound(pLExpr);
									aExpr2.setPLBound(pLExpr);									
								}	
								else if(rExpr.getLower() instanceof MinusExpr)
								{
									MinusExpr mLExpr=(MinusExpr)rExpr.getLower();
									aExpr1.setMLBound(mLExpr);
									aExpr2.setMLBound(mLExpr);
								}								
							}//end of else if
						}//end of 2nd if					
					}//end of 1st if
			 }//end of for loop		
		  }//end of function.


}