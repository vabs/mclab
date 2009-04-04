package natlab;

import java.util.ArrayList;
import java.util.HashSet;

import natlab.ast.*;

import natlab.SymbolTableScope;
import natlab.SymbolTableEntry;

import annotations.ast.ArgTupleType;
import annotations.ast.MatrixType;
import annotations.ast.Type;
import annotations.ast.PrimitiveType;
import annotations.ast.Size;
import annotations.ast.UnknownType;

/**
 * A utility for TypeInferenceEngine
 * 
 * It's an Engine, handles request inferType(...) from different ASTNode,
 * which defined by 'TypeCollection.jrag'.
 *
 * TODO: 
 * 		1. Split transformations into another class
 */
public class TypeInferenceEngine {

	static public boolean DEBUG = false;
	static public int loopCounter = 1;

    // set inside the transposeType(), to indicate that the new inferred type 
    // (matrix type) is different to/bigger than pre-inferred, so need to adjust the previous 
    // define-node and use-node of same variable.
	static public boolean bAdjustArrayAccess = false;
		
	// Type name 
	static final public String TYPENAME_INTEGER = "int";
	static final public String TYPENAME_DOUBLE = "double";
	static final public String TYPENAME_FLOAT = "float";
	static final public String TYPENAME_LOGICAL = "logical";
	static final public String TYPENAME_COMPLEX = "complex";
	
	static final public String TYPENAME_CHARACTER = "character";

	public static final String TEMP_VAR_PREFIX = "tmpvar_";	
	static final public String PHI_FUNC_NAME = "PHISSA";

	// Constant for index
	static final int ERROR_EXTENT = -9999;
	static final int UNKNOWN_EXTENT = 9999;

	// A set of intrinsic function node, which is in command form
	static HashSet<NameExpr> cmdFormFuncList = new HashSet<NameExpr>();
	static SymbolTableScope gstScope;

	static public class Exception extends java.lang.Exception
	{
		/**
		 * 
		 */
		private static final long serialVersionUID = 1L;

		Exception(String msg)
		{
			super(msg);
		}
	}
	public TypeInferenceEngine() {
	}
	
	// Purpose: create new name for renaming
	public static String getNewVarName(String orgName, ASTNode node) {
		return orgName+"_r"+(++node.VarCnt);
	}
	public static String getTempVarName(ASTNode node) {
		return TEMP_VAR_PREFIX+node.getNodeID()+(++node.VarCnt);
	}

	public static Type inferType(SymbolTableScope stScope, NameExpr node, ASTNode varNode) 
	{
		String varName = node.getName().getID();
		Type varType = null;
		// Handle built-in values: pi, NaN, ...
		if(varName.equals("pi") || varName.equals("NaN")) {
			// Should every program add a line of pi=3.
			varType = new PrimitiveType(TYPENAME_DOUBLE);
		} else {		
			// Handle variables
	        SymbolTableEntry stEntry = stScope.getSymbolById(node.getName().getID());
	        if(stEntry==null) {
	        	return inferTypeIntrinsicFunction(node);
	        }
	        if(stEntry.getDeclLocation()!=null) {
	        	VariableDecl varDecl = (VariableDecl) stEntry.getDeclLocation();
	        	varType = varDecl.getType();
	        } else {
	    		// assert(varDecl.getType()!=null) 
	    		// This case, the Variable doesn't initialize, therefore 
	    		// the parser should report error
	        	System.err.println("[inferType]-NameExpr: Null varDecl "+node.getStructureStringLessComments());
	        	return null;
	        }
		}
		// LHS Range adjustment will not happen here,  
        if (varNode instanceof ParameterizedExpr && 
        		!(varType instanceof MatrixType)) {
        	System.err.println("[inferType] adjustVariableType ["+varName+"]="+varType+ " ["+(varType==null?"":varType.getName())+"]");        	
        }

    	return varType;
	}
	
	public static Type inferType(SymbolTableScope stScope, 
			UnaryExpr expr, Type opType, ASTNode varNode)
	{
		Type varType = opType;
		if(expr instanceof MTransposeExpr || expr instanceof ArrayTransposeExpr) {
			return transposeType(opType);
		}
		return varType;
	}
	// Flip a matrix about its main diagonal, turning row
	// vectors into column vectors and vice versa.
	private static Type transposeType(Type opType) {
		if(opType instanceof MatrixType) {
			Size size = ((MatrixType) opType).getSize();
			java.util.List<Integer> dim = new ArrayList<Integer>();
			MatrixType mType = new MatrixType(((MatrixType) opType).getElementType());
			if(size.getDims()!=null) {
				// Special case: [0, 1]'
				if(size.getDims().size()==1) {
					// Update the argument's type, and the expression will be adjusted
					// during 2nd time adjustment pass.
					// i.g.: tmp1=[1,2], tmp2=TRANSPOSE(tmp1); => tmp1={1,2}
					//		=> tmp1(1,:)=[1,2]
					// result type
					dim.add(size.getDims().get(0));
					dim.add(1);
					
					// update current matrix type 
					java.util.List<Integer> newdim = new ArrayList<Integer>();
					newdim.add(1); newdim.add(size.getDims().get(0));
					((MatrixType) opType).setSize(new Size(newdim));
					// Set flag for further adjusting matrix access
					bAdjustArrayAccess = true;
					
				} else {
					dim.add(size.getDims().get(1));
					dim.add(size.getDims().get(0));
				}
				mType.setSize(new Size(dim));
				return mType;

			} else {
				java.util.List<String> vstrDims = size.getDynamicDims();
				java.util.List<String> mstrDims = new ArrayList<String>();
				if(vstrDims.size()==1) {
					// result type
					mstrDims.add(vstrDims.get(0));
					mstrDims.add("1");

					// update current matrix type 
					bAdjustArrayAccess = true;
					java.util.List<String> newDims = new ArrayList<String>();
					newDims.add("1");	newDims.add(vstrDims.get(0));
					Size newSize = new Size();
					newSize.setDynamicDims(newDims);
					((MatrixType) opType).setSize(newSize);
					
				} else {
					// Flip dimensions 
					for(int i=vstrDims.size()-1; i>=0; i--) {
						mstrDims.add(vstrDims.get(i));
					}
				}
				Size msize = new Size();
				msize.setDynamicDims(mstrDims);
				mType.setSize(msize);
				return mType;
			}
		}
		return opType;
	}
	
	// Transform a command form function call into parameterized form
	public static void transformCmdFormFunction() {
		for(NameExpr node: cmdFormFuncList) {
			transform2ParameterizedExpr(node);
		}
		cmdFormFuncList.clear();	// otherwise will generate more useless expr.
	}

	private static void transform2ParameterizedExpr(Expr node, NameExpr newNode, Expr...exprs) {
    	ASTNode parent = node.getParent();
    	int loc = parent.getIndexOfChild(node);
    	natlab.ast.List<Expr> list = new natlab.ast.List<Expr>();
		for(Expr expr: exprs) {
			list.add(expr);
		}
    	ParameterizedExpr funcExpr = new ParameterizedExpr(newNode, list);
    	parent.setChild(funcExpr, loc);
	}
	private static void transform2ParameterizedExpr(NameExpr node) {
		transform2ParameterizedExpr(node,node,new Expr[0]);
		/*
    	ASTNode parent = node.getParent();
    	int loc = parent.getIndexOfChild(node);
				+" parent=["+parent.getNodeID()+"] "+parent );
    	natlab.ast.List<Expr> list = new natlab.ast.List<Expr>();
    	ParameterizedExpr funcExpr = new ParameterizedExpr(node, list);
    	parent.setChild(funcExpr, loc);
    	*/
	}
	// Transform a expression into logical expression, and update the AST
	public static Expr transform2LogicalOnly(Expr oprandExpr, Type typeExpr) {
		if(typeExpr instanceof PrimitiveType) {
			if(typeExpr.getName().equalsIgnoreCase(TYPENAME_INTEGER)
						|| typeExpr.getName().equalsIgnoreCase(TYPENAME_DOUBLE)) {
				NEExpr newExpr=new NEExpr();  
				if(typeExpr.getName().equalsIgnoreCase(TYPENAME_INTEGER)) {
					newExpr = new NEExpr(oprandExpr, new IntLiteralExpr(new natlab.DecIntNumericLiteralValue("0")));
				} else {
					newExpr = new NEExpr(oprandExpr, new FPLiteralExpr(new natlab.FPNumericLiteralValue("0.0")));
				}
				return newExpr;
			}
		}
		return null;
		// Other case character(50). ..
	}
	private static void transform2Logical(BinaryExpr expr, Expr oprandExpr, Type typeExpr, boolean lhs) {
		if(typeExpr instanceof PrimitiveType) {
			if(typeExpr.getName().equalsIgnoreCase(TYPENAME_INTEGER)
						|| typeExpr.getName().equalsIgnoreCase(TYPENAME_DOUBLE)) {
				NEExpr newExpr=new NEExpr();  
				if(typeExpr.getName().equalsIgnoreCase(TYPENAME_INTEGER)) {
					newExpr = new NEExpr(oprandExpr, new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue("0")));
				} else {
					newExpr = new NEExpr(oprandExpr, new FPLiteralExpr(new natlab.FPNumericLiteralValue("0.0")));
				}
				if(lhs) {	
					expr.setLHS(newExpr);
				} else {
					expr.setRHS(newExpr);
				}
			}
		}
		// Other case: character(50). ..
	}
	
	// For an function, which only take floating-point argument,
	// need to transform the integer argument to floating-point
	// Parameters:
	//	- expr: the function expression
	//	- argNum: how many arguments it expects
	private static void transformIntegerArg2Double(SymbolTableScope stScope, 
			ParameterizedExpr expr, int argNum) {
		// Transform on INTEGER argument
		int i=0;
		for(Expr arg: expr.getArgs()) {
        	if(isIntLiteral(arg)) {
        		double value = (double)getIntLiteralValue(arg);
        		Expr argDobule = new FPLiteralExpr(new natlab.FPNumericLiteralValue(""+value));
        		expr.setArg(argDobule, i);            		
        	} else if(arg instanceof NameExpr) {
				SymbolTableEntry e=stScope.getSymbolById(arg.getVarName());
				if(e!=null) {
	    			Type argType = ((VariableDecl)e.getDeclLocation()).getType();
	    			if(isIntegerType(argType)) {
	    				PlusExpr newPlus = new PlusExpr(arg, 
	    						new FPLiteralExpr(new natlab.FPNumericLiteralValue("0.0")));
	            		expr.setArg(newPlus, i);            		
	    			}
				}
	    	}	
	    	i++; 
	    	if(i>=argNum)
	    		break;
		}
	}
	
	// Only transform the expression, don't modify the variable's type
	// Because some variable should keep integer, i.g. loop-index variables
	private static void transformInteger2Double(Expr expr) {
		ASTNode parent = expr.getParent();
    	int loc = parent.getIndexOfChild(expr);
		PlusExpr newPlus = new PlusExpr(expr, 
				new FPLiteralExpr(new natlab.FPNumericLiteralValue("0.0")));
		parent.setChild(newPlus, loc);            		
	}
	
	public static int getDimensionNumber(MatrixType mType) {
		int num = 0;
    	if(mType.getSize().getDims()!=null) {
    		num = mType.getSize().getDims().size();
    	} else if(mType.getSize().getDynamicDims()!=null) {
    		num = mType.getSize().getDynamicDims().size();
    	}		
		return num;
	}
	
	public static boolean isEqualType(Type type1, Type type2 ) {
		boolean bResult = false;
		if(type1!=null && type2!=null) {
			if(type1 instanceof PrimitiveType && type2 instanceof PrimitiveType) {
				bResult =(type1.getName().equals(type2.getName()));
			} else if ((type1 instanceof MatrixType) && (type2 instanceof MatrixType)) {
				// Otherwise, there are both MatrixType, check number of dimensions
				int dimNum1= getDimensionNumber((MatrixType) type1);
				int dimNum2= getDimensionNumber((MatrixType) type2);
				bResult = dimNum1==dimNum2;
			}
		}
		return bResult;
	}
	public static Type inferType(SymbolTableScope stScope, 
			BinaryExpr expr, Type lhsType, Type rhsType, ASTNode varNode)
	{
		Type varType = null;

		// (1) Logical binary operations, generate 'logical' type
		if(expr instanceof AndExpr || expr instanceof ShortCircuitAndExpr
				|| expr instanceof OrExpr || expr instanceof ShortCircuitOrExpr
				|| expr instanceof LTExpr || expr instanceof GTExpr
				|| expr instanceof LEExpr || expr instanceof GEExpr
				|| expr instanceof EQExpr || expr instanceof NEExpr)
		{
			varType = new PrimitiveType(TYPENAME_LOGICAL);
			
			if(expr instanceof AndExpr || expr instanceof ShortCircuitAndExpr
					|| expr instanceof OrExpr || expr instanceof ShortCircuitOrExpr)
			{
				// TODO: expr will be enforced as new Type
				Type lType = expr.getLHS().collectType(stScope, varNode);
				if (!isEqualType(varType, lType)) {
					transform2Logical(expr, expr.getLHS(),lType,true);
				}
				Type rType = expr.getRHS().collectType(stScope, varNode);
				if (!isEqualType(varType, rType)) {
					transform2Logical(expr, expr.getRHS(),rType,false);
				}
			} else {
				// Return type is one of the element's type
				// Data>0, 
				PrimitiveType pType = new PrimitiveType(TYPENAME_LOGICAL);
				if(lhsType instanceof MatrixType) {
					varType = createMatrixType(TYPENAME_LOGICAL, (MatrixType)lhsType);
					pType = (PrimitiveType)((MatrixType)lhsType).getElementType();
				} else if(rhsType instanceof MatrixType) {
					varType = createMatrixType(TYPENAME_LOGICAL, (MatrixType)rhsType);
					pType = (PrimitiveType)((MatrixType)rhsType).getElementType();
				}
				
				if(varType instanceof MatrixType) {
	            	// (1). remember current location
					ASTNode parent = expr.getParent();
	            	int loc = parent.getIndexOfChild(expr);

	            	// (2) Create new variable = this new parameterized-expression            	
	            	NameExpr newVar = addNewAssignment(expr, expr, null, stScope, varType);
	            	
	            	// (3) Add more assignment when necessary
	            	//		Using parent, because the expr is lost, becomes part of new assignment
	            	if(isDoubleType(pType)) {
	            		MatrixType imType = createMatrixType(TYPENAME_INTEGER, (MatrixType)varType);
	            		NameExpr newVar2 = addNewAssignment(newVar, parent, null, stScope, imType);
	            		newVar = newVar2;
	            	}
	            	
	            	
	            	// (3) replace by temporary assignment 
	            	parent.setChild(newVar, loc);
	            	// strExtent = newVar.getName().getID();
            	
				}
			}
			
		} else {
			// (2) Mathematical binary-expression
			// Result type need be calculated based on the operand type and operator
			if(lhsType==null) {
				return rhsType; 
			} else if(rhsType==null) {
				return lhsType; 
			} else { // (lhsType!=null && rhsType!=null) 
				varType = lhsType;
				if((lhsType instanceof MatrixType) && (rhsType instanceof MatrixType)) {
					PrimitiveType pType = mergePrimitiveType((PrimitiveType)((MatrixType)lhsType).getElementType(),
							(PrimitiveType)((MatrixType)rhsType).getElementType());
					// Check the conform-able when either one is a matrix!					
					java.util.List<Integer> dim = new ArrayList<Integer>();
					java.util.List<Integer> lDims = ((MatrixType) lhsType).getSize().getDims();
					java.util.List<Integer> rDims = ((MatrixType) rhsType).getSize().getDims();
					if(expr instanceof MTimesExpr) {
						if(lDims!=null && rDims!=null) {
							// For dimensions are static integers
							lDims = convertVectorDimension(lDims);
							rDims = convertVectorDimension(rDims);
							// At least one is 2 dimension
							if(lDims.size()==2 && rDims.size()==2) {
								if(lDims.get(1)==rDims.get(0)) {
									dim.add(lDims.get(0));
									dim.add(rDims.get(1));
									//Even if(dim.get(0)==1  && dim.get(1)==1), it's a 1x1, NOT a scalar 
									MatrixType mType = new MatrixType(pType);
									mType.setSize(new Size(dim));
									return mType;
	
								}
							}
						} else {
							// For dynamic dimensions
							fillStringDimensions(((MatrixType) lhsType), ((MatrixType) rhsType));
							java.util.List<String> lstrDims = ((MatrixType) lhsType).getSize().getDynamicDims();
							java.util.List<String> rstrDims = ((MatrixType) rhsType).getSize().getDynamicDims();
							// convert Vector Dimension, to 2-dimensions, update its type
							if(lstrDims.size()==1) {
								lstrDims.add(0,"1");
								((MatrixType) lhsType).getSize().setDynamicDims(lstrDims);
							}
							if(rstrDims.size()==1) {
								rstrDims.add(0,"1");
								((MatrixType) rhsType).getSize().setDynamicDims(rstrDims);
							}
							
							if(lstrDims.size()==2 && rstrDims.size()==2) {
								java.util.List<String> strDims = new ArrayList<String>();
								if(lstrDims.get(1)!=rstrDims.get(0)) {
									if(expr.getLHS().getVarName().equals(expr.getRHS().getVarName())) {
										// Extent to the larger one
										// This is an special solution for benchmark 'clos'
										// B*B, B{N,N+N}
										String str0 = lstrDims.get(0);
										String str1 = lstrDims.get(1);
										str0 = StaticFortranTool.selectLargerExpressions(str0, str1);
										lstrDims.set(0, str0);	lstrDims.set(1, str0);
										rstrDims.set(0, str0);	rstrDims.set(1, str0);
										System.err.println("[inferType-BinaryExpr]"+" special case!");
									}
									
								}
								if(lstrDims.get(1).equals(rstrDims.get(0))
										|| StaticFortranTool.COMPARE_EQUAL==StaticFortranTool.compareExpressions(lstrDims.get(1), rstrDims.get(0))) {
									strDims.add(lstrDims.get(0));
									strDims.add(rstrDims.get(1));
									//Even if(dim.get(0)==1  && dim.get(1)==1), it's a 1x1, NOT a scalar 
									MatrixType mType = new MatrixType(pType);
									Size strSize = new Size();
									strSize.setDynamicDims(strDims);
									mType.setSize(strSize);
									return mType;
	
								} else {
									// That's a semantic  error
									System.err.println("[inferType-BinaryExpr]"+expr.getStructureString()+" two operand types are not conformable!!");
								}
							}
							
						}
						System.err.println("[inferType-BinaryExpr]"+expr.getStructureString()+" two operand types are not conformable!");

					} else if((expr instanceof PlusExpr) || (expr instanceof ETimesExpr)
								|| (expr instanceof EDivExpr)) {
						// Check the conform-able, (two operands should have same size/dimension)
						boolean isConformable = false;
						if(lDims!=null && rDims!=null) {
							// Only static type can be compare 
							if(lDims.size()==rDims.size()) {
								isConformable = true;
								for(int i=0; i<lDims.size(); i++) {
									if(lDims.get(i)!=rDims.get(i)) {
										isConformable = false;
										break;
									}
								}
							}
						} else {
							// Assume the are equal, using the larger one
							isConformable = true;
							// They are dynamic dimension,
							java.util.List<String> strDims = new ArrayList<String>() ;
							java.util.List<String> lstrDims = ((MatrixType) lhsType).getSize().getDynamicDims();
							java.util.List<String> rstrDims = ((MatrixType) rhsType).getSize().getDynamicDims();
							for(int i=0; i<lstrDims.size() && i<rstrDims.size(); i++) {
								String str = StaticFortranTool.selectLargerExpressions(lstrDims.get(i), rstrDims.get(i));
								strDims.add(str);
							}
							MatrixType mType = new MatrixType(pType);
							Size strSize = new Size();
							strSize.setDynamicDims(strDims);
							mType.setSize(strSize);
							varType = mType;
							return mType;
						}
						if(isConformable) {
							// Decide the final type, equals to one operand's
							varType = lhsType;
						} else {
							System.err.println("[inferType-BinaryExpr]"+" two operand types are not conformable! ");								
						}
					} else {
						// TODO: Other operations ....
					}			
					
				// If one is matrix and another is scalar, return matrix, with larger primary type
				} else if(lhsType instanceof MatrixType) {
					varType = mergeMatrixType(rhsType, (MatrixType) lhsType);
				} else if(rhsType instanceof MatrixType) {
					varType = mergeMatrixType(lhsType, (MatrixType) rhsType);
				} else {	// all primitive type
					varType =  mergePrimitiveType(rhsType, lhsType);;
					// Because integer division will lost data, so convert them to double, 
					if(expr instanceof MDivExpr) {
						if(isIntegerType(varType)) {
							// If this expression is not belong to any array index
							if(!ASTToolBox.isInsideArray(expr)) {
								// Transform LHS to an double expression
								transformInteger2Double(expr.getLHS());
								varType = new PrimitiveType(TYPENAME_DOUBLE);
							}
						}
					}
				}
			} // if (lhsType!=null && rhsType!=null) 
			// TODO: Otherwise, we cannot decide what type it is, it's a DYNAMIC type
		}
		
        // DEBUG
		return varType;
	}
	
	// If it's a row vector A(5), translate into A(1,5)
	public static java.util.List<Integer> convertVectorDimension(java.util.List<Integer> dims) {
		if(dims==null)
			return null;
		if (dims.size()==1) {
			dims.add(0,1);
		}
		return dims;
	}
	public static java.util.List<String> convertStringVectorDimension(java.util.List<String> dims) {
		if(dims==null)
			return null;
		if (dims.size()==1) {
			dims.add(0,"1");
		}
		return dims;
	}

	public static Type inferType(SymbolTableScope stScope, 
			Row expr, ASTNode varNode) 
	{
		Type elementType=null;
		Type tmpType=null;
		int totalNum = 0;
		// 1. Calling to next level 
        List<Expr> elements = expr.getElements();
        for(Expr element : elements) {
			tmpType = element.collectType(stScope, varNode);
			if(tmpType!=null) {
				if(isCharacterType(tmpType)) {
					// Add each row's length 
					int loc1= tmpType.getName().indexOf("(");
					int loc2= tmpType.getName().indexOf(")");					
					totalNum += (new Integer(tmpType.getName().substring(loc1+1,loc2))).intValue();
				}
				if(elementType == null)	{
					elementType = tmpType;
				} else if(elementType instanceof PrimitiveType 
						&& tmpType instanceof PrimitiveType) {
					elementType = mergePrimitiveType(elementType, tmpType);
				} else {
					// One of them is MatrixType
					PrimitiveType pType = (PrimitiveType) mergePrimitiveType(elementType, tmpType);
					MatrixType mType; 
					MatrixType oneType = new MatrixType(pType); 
					Size oneSize = new Size();
					java.util.List<Integer> oneDims = new ArrayList<Integer>();	oneDims.add(1);
			    	java.util.List<String> oneStrDims = new ArrayList<String>(); oneStrDims.add("1");
			    	oneSize.setDims(oneDims);
			    	oneSize.setDynamicDims(oneStrDims);
			    	oneType.setSize(oneSize);
			    	
					if(elementType instanceof MatrixType && tmpType instanceof MatrixType) {
						mType = createMatrixType(pType.getName(),(MatrixType)elementType, (MatrixType)tmpType, COMBINE_ROW);
					} else if(elementType instanceof MatrixType) {
						mType = createMatrixType(pType.getName(),(MatrixType)elementType, oneType, COMBINE_ROW);
					} else {
						mType = createMatrixType(pType.getName(),(MatrixType)tmpType, oneType, COMBINE_ROW);
					}
					return mType;
				}
				/**
				if(elementType == null)	{
					elementType = tmpType;
				} 
				if(elementType.getName().equals(TYPENAME_DOUBLE)) {
					break;
				} else if(tmpType.getName().equals(TYPENAME_DOUBLE)) {
					elementType = tmpType;
					break;
				} else if(isCharacterType(tmpType)){ 
					// Doing string concatenation ['ab','cdf'] ='abcdf'
					// And need to know length of each of them
					int loc1= tmpType.getName().indexOf("(");
					int loc2= tmpType.getName().indexOf(")");					
					totalNum += (new Integer(tmpType.getName().substring(loc1+1,loc2))).intValue();
				} **/
			} else {
				elementType = null;
			}
         }
        if(isCharacterType(tmpType)) {
			// Doing string concatenation ['ab','cdf'] ='abcdf'
			// And need to know length of each of them
			elementType = new PrimitiveType("character("+(totalNum)+")");
		}
        
		// 2. Don't change the row itself 
		return elementType;		
	}
	
	//	(1) First KNOWN element's type is the element type
	//	(2) Calculate the #Row and #column
	// 	(3) #column may should be the maximum one
	public static Type inferType(SymbolTableScope stScope, 
			MatrixExpr expr, ASTNode varNode) 
	{
		// previous rows's type (merged)
		Type elementType=null;	
		Type tmpType=null;
		// 1. Calling to next level 
        List<Row> rows = expr.getRows();
		int maxColumn=0;
		int rowElem, totalNum=0;
		// record type of each row
		Type[] rowTypes = new Type[rows.getNumChild()];	
		int rowNum=0;
        for(Row row : rows) {
        	// This one may NOT check all row, because the 'break' below
        	rowElem = row.getElements().getNumChild();
        	if(maxColumn < rowElem)
        		maxColumn = rowElem;
        		
			tmpType = row.collectType(stScope, varNode);
			rowTypes[rowNum] = tmpType;

			// Support elements with primitive type or matrix 
			if(tmpType!=null) {
				if(isCharacterType(tmpType)) {
					// Add each row's length 
					int loc1= tmpType.getName().indexOf("(");
					int loc2= tmpType.getName().indexOf(")");					
					totalNum += (new Integer(tmpType.getName().substring(loc1+1,loc2))).intValue();
				}
				if(rowNum==0) {	// first row
					elementType = tmpType;
				} else if(elementType instanceof PrimitiveType 
						&& tmpType instanceof PrimitiveType) {
					elementType = mergePrimitiveType(elementType, tmpType);
				} else {
					// One of them is MatrixType
					PrimitiveType pType = (PrimitiveType) mergePrimitiveType(elementType, tmpType);
					MatrixType oneType = new MatrixType(pType); 
					Size oneSize = new Size();
					java.util.List<Integer> oneDims = new ArrayList<Integer>();	oneDims.add(1);
			    	java.util.List<String> oneStrDims = new ArrayList<String>(); oneStrDims.add("1");
			    	oneSize.setDims(oneDims);
			    	oneSize.setDynamicDims(oneStrDims);
			    	oneType.setSize(oneSize);
			    	
					if(elementType instanceof MatrixType && tmpType instanceof MatrixType) {
						elementType = createMatrixType(pType.getName(),(MatrixType)elementType, (MatrixType)tmpType, COMBINE_COLUMN);						
					} else if(elementType instanceof MatrixType) {
						elementType = createMatrixType(pType.getName(),(MatrixType)elementType, oneType, COMBINE_COLUMN);
					} else {
						elementType = createMatrixType(pType.getName(),(MatrixType)tmpType, oneType, COMBINE_COLUMN);
					}
				}
			} else {
				elementType = null;
				System.err.println("Error: cannot infer the type of current row!");
			}
			rowNum++;
        }
        // If the elementType is PrimitiveType, here adding the dimensions, 
        if(elementType!=null && (elementType instanceof PrimitiveType) ) {
			java.util.List<Integer> dim = new java.util.ArrayList<Integer>();
			Type varType; 
			
			// Doing string concatenation ['ab','cd'] ='abcd', generate Primitive-Type
	        // MATLAB doesn't allow vertical concatenation, ['ab';'cd'] is illegal!
	        if(isCharacterType(elementType)) {
				varType = new PrimitiveType("character("+(totalNum)+")");
				// The whole string concatenation is happened in Row.getFortran() 
			} else {
				// Form the matrix type = {rows,columns}
				if(rows.getNumChild()>1)
					dim.add(rows.getNumChild());
				dim.add(maxColumn);
				varType = new MatrixType(new PrimitiveType(elementType.getName()));
				((MatrixType) varType).setSize(new Size(dim));
				
				// Transform into separate assignments, one for each row
				if(rows.getNumChild()>1
						&& expr.getParent() instanceof AssignStmt 
						&& ((AssignStmt) expr.getParent()).getLHS() instanceof NameExpr) {
	            	// (1). remember current location
					AssignStmt parentAsg = (AssignStmt) expr.getParent();
					ASTNode parent = parentAsg.getParent();	// it should be a list
	            	int loc = parent.getIndexOfChild(parentAsg);
	            	if(loc>0) {
		            	NameExpr varExpr = (NameExpr)((AssignStmt) expr.getParent()).getLHS();
	            		// this node is changed, but the code-node doesn't 
	            		// so this code-node doesn't link to any parent, loc=-1

		            	for(int index=rows.getNumChild();index>0;index--)
				        {
		            		Row row = rows.getChild(index-1);
				    		AssignStmt rowAsg = new AssignStmt();
				    		// Split the row
							// 	m3=[9,8,7;6,5,4;11,12,13;15,16,17]; => m3(1,:)=[9,8,7], ...
			            	natlab.ast.List<Expr> list = new natlab.ast.List<Expr>();
		    				list.add(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue(""+index)));	                				
				    		list.add(new ColonExpr());
			            	ParameterizedExpr exprParam = new ParameterizedExpr(varExpr, list);			    		
				    		rowAsg.setLHS(exprParam);
				    		
			            	// One row matrix is RHS
			            	List<Row> rowlist = new List<Row>();
			            	rowlist.add(row);
				    		rowAsg.setRHS(new MatrixExpr(rowlist));
				    				            	
			            	// (3) replace by temporary assignment 
			            	if(index==1) {
			            		parent.setChild(rowAsg, loc);
			            	} else {
			            		parent.insertChild(rowAsg, loc+1);
			            	}
				        }
	            	}
				}
			}
	        return varType;
		} else {
			if(elementType!=null && (elementType instanceof MatrixType) ) {
				// Transform C=[A;B]
				transformMatrixConcatenation(expr, (MatrixType)elementType, rowTypes);
			} 
        	return elementType;
        }
	}
	// Transform matrix construction and concatenation into separate assignment
	public static void transformMatrixConcatenation(MatrixExpr expr, 
				MatrixType mType, Type[] rowTypes) {
		// This is the basic structure of the matrix assignment
		if( expr.getParent() instanceof AssignStmt 
				&& ((AssignStmt) expr.getParent()).getLHS() instanceof NameExpr
				&& ((AssignStmt) expr.getParent()).getRHS()==expr) {
			// (1). remember current location
			AssignStmt parentAsg = (AssignStmt) expr.getParent();
			NameExpr varExpr = (NameExpr)parentAsg.getLHS(); 
			// When there is only one element, then 
			// Remove the matrix expression to normal expression
			if(rowTypes.length==1) {
				if(((Row) expr.getRow(0)).getNumElement()==1) {
					ASTNode parent = expr.getParent();
					int loc = parent.getIndexOfChild(expr);
					parent.setChild(((Row) expr.getRow(0)).getElement(0), loc);
				} else {					
					ASTNode parent = parentAsg.getParent();	// it should be a list
	            	int loc = parent.getIndexOfChild(parentAsg);
					// m6 = [m12, m34]
					// m6(1,:) = [m12(1,:), m34(1,:)]
					// m6(2,:) = [m12(2,:), m34(2,:)]
		        	java.util.List<Integer> mDims = ((MatrixType)mType).getSize().getDims();
		        	if(mDims!=null) {
		        		int rowStart = 0;
						for(int i=0; i<mDims.get(0); i++) {
				    		rowStart++;
				    		AssignStmt rowAsg = new AssignStmt();
			            	natlab.ast.List<Expr> list = new natlab.ast.List<Expr>();
		    				list.add(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue(""+rowStart)));
				    		list.add(new ColonExpr());
			            	ParameterizedExpr exprParam = new ParameterizedExpr(varExpr, list);			    		
				    		rowAsg.setLHS(exprParam);
				    		
			            	// Adjusted row matrix is RHS
			            	natlab.ast.List<Expr> rlist = new natlab.ast.List<Expr>();
			            	// there is only one row!
				    		Row row = expr.getRows().getChild(0);
				    		for(Expr element: row.getElements()) {
				            	natlab.ast.List<Expr> elist = new natlab.ast.List<Expr>();
			    				elist.add(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue(""+rowStart)));
					    		elist.add(new ColonExpr());
				            	ParameterizedExpr elemParam = new ParameterizedExpr(element, elist);
				            	rlist.add(elemParam);
				    		}
			            	List<Row> rowlist = new List<Row>();
			            	rowlist.add(new Row(rlist));
				    		rowAsg.setRHS(new MatrixExpr(rowlist));
				    		
			            	if(rowStart==1) {
			            		parent.setChild(rowAsg, loc);	
			            	} else {
			            		parent.insertChild(rowAsg, loc+rowStart-1);
			            	}
							
						}
		        	}					
				}
				
			} else if(rowTypes.length>1) {
	    		// m12=[r1,r2] ==> m12(1, :) = r1; m12(2, :) = r2;
				// m5=[m12; m34] ==>  m5(1 : 2, :) = m12; m5(3 : 4, :) = m34;
				// If there are more rows, then generate assignment for each of them
				ASTNode parent = parentAsg.getParent();	// it should be a list
            	int loc = parent.getIndexOfChild(parentAsg);

            	int rowStart = 1;
            	for(int index=1; index<=expr.getRows().getNumChild();index++)
		        {
					// create new assign, with new LHS,RHS
            		Row row = expr.getRows().getChild(index-1);
		    		AssignStmt rowAsg = new AssignStmt();
	            	natlab.ast.List<Expr> list = new natlab.ast.List<Expr>();
		    		// Split the row
					// 	C=[A;B];  => C(1,:)=A; C(2,:)=B  
            		Type rowType = rowTypes[index-1];
					// (rowType instanceof MatrixType)
		        	java.util.List<Integer> lDims = ((MatrixType)rowType).getSize().getDims();
		        	if(lDims!=null) {
						if(lDims.size()==1) {
		    				list.add(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue(""+rowStart)));
				    		list.add(new ColonExpr());
				    		rowStart++;
						} else if(lDims.size()==2) {
							RangeExpr newRange = new RangeExpr();
							if(lDims.get(0)==1) {
			    				list.add(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue(""+rowStart)));
					    		rowStart++;
							} else {
								newRange.setLower(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue(""+rowStart)));
								newRange.setUpper(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue(""+(rowStart+lDims.get(0)-1))));
			    				list.add(newRange);
					    		rowStart+=lDims.get(0);
							}
				    		list.add(new ColonExpr());
						}
		        	}
	            	ParameterizedExpr exprParam = new ParameterizedExpr(varExpr, list);			    		
		    		rowAsg.setLHS(exprParam);
		    		
	            	// One row content is RHS
		    		if(row.getNumElement()>1) {
		    			System.err.println("Error: matrix concatenation doesn't support multiple row/column of matrix elements.");
		    		}
		    		rowAsg.setRHS(row.getElement(0));
	            	if(index==1) {
	            		parent.setChild(rowAsg, loc);	
	            	} else {
	            		parent.insertChild(rowAsg, loc+index-1);
	            	}
				}				
			} // if(rowTypes.length>1)
			
		}
	}

	// Utility Functions for range expression
	// # 2nd time calling RangeExpr.inferType() should not trigger following transformation
	// Because the transformation will 
	//	- Transform the floating point range expression to integer range expression
	//	- lower, upper, incr will all become integer
	// For constant range:
	// 	- it relies on the constant propagation to convert variable range into constant range
	// For variable range:
	//	- it relies on the transformation to create new variable for extent
	public static String getStringExtent(SymbolTableScope stScope, String exprStr, boolean isInteger) {
		// This is a temporary solution!
		// This can be handled by saving the range expression in the Symbol Table,
		// at the time when calculating the value and saving it to the Symbol Table Entry.
		String strExtent = null;
		ExprStmt exprStmt =  StaticFortranTool.parseString(exprStr);
		Expr expr = exprStmt.getExpr();
		if(expr instanceof RangeExpr) {
			return getStringExtent(stScope, (RangeExpr)expr, isInteger);
		}		
		return strExtent;
	}
	
	public static String getStringExtent(SymbolTableScope stScope, RangeExpr expr, boolean isInteger) {
		String strExtent = null;
		// This is handled by inferSymbolEntry(), because it needs 2 values
		// following is dummy code
		// TODO: Need to calculate the value of Range Expr!
		String strLow = getVariableValue(stScope, ((RangeExpr)expr).getLower());
		String strUpp = getVariableValue(stScope, ((RangeExpr)expr).getUpper());
		if(((RangeExpr)expr).hasIncr()) {
			strExtent = strUpp + "-" + strLow;
			strExtent = "("+strExtent+")/"+getVariableValue(stScope, ((RangeExpr)expr).getUpper());
			strExtent = strExtent + "+1" ;
		} else {
			strExtent = strUpp + "-" + strLow;
			strExtent = strExtent + "+1" ;
		}
		return strExtent;
	}
	
	public static int getIntegerExtent(RangeExpr expr, boolean isInteger) {
		int extent = ERROR_EXTENT;
		if(isInteger) {
			int inc = 1;
			if(expr.hasIncr() && (expr.getIncr()!=null && isIntLiteral(expr.getIncr()))) {
				inc = getIntLiteralValue(expr.getIncr());
			}				 
			int first = getIntLiteralValue(expr.getLower());
			int last = 0;
			if(expr.getUpper() instanceof IntLiteralExpr) {
				last = getIntLiteralValue(expr.getUpper());
			} else {
				last = (int) getFPLiteralValue(expr.getUpper());
			}
			extent = 1 + Math.abs(last-first)/Math.abs(inc);
		} else {
			double inc = 1;
			if(expr.hasIncr() && (expr.getIncr()!=null)) { 
				if(isIntLiteral(expr.getIncr())) {
					inc = getIntLiteralValue(expr.getIncr());
				} else {
					inc = getFPLiteralValue(expr.getIncr());
				}
			}				 
			double first = 0;
			if(expr.getLower() instanceof IntLiteralExpr) {
				first = getIntLiteralValue(expr.getLower());
			} else {
				first = getFPLiteralValue(expr.getLower());
			}
			double last = 0;
			if(expr.getLower() instanceof IntLiteralExpr) {
				last = getIntLiteralValue(expr.getUpper());
			} else {
				last = getFPLiteralValue(expr.getUpper());
			}
			extent = (int) (1 + Math.abs(last-first)/Math.abs(inc));
		}

		return extent;
	}
	
	public static Type inferType(SymbolTableScope stScope, 
			RangeExpr expr, ASTNode varNode) 
	{
		boolean bTransform = false;
		String strExtent="";
		int extent = ERROR_EXTENT;
		boolean isInteger = false;
		boolean bForStmtIndex = false;
		
    	Stmt stmt = ASTToolBox.getParentStmtNode(expr);
    	if(stmt instanceof AssignStmt && stmt.getParent() instanceof ForStmt) {
    		bForStmtIndex = true;
    	}

    	//  If first element or increase element is Double, then Double
		MatrixType mType;		
		if(isFPLiteral(expr.getLower()) 
				|| isDoubleType(expr.getLower().collectType(stScope, expr))
				|| (expr.hasIncr() && (expr.getIncr()!=null && isFPLiteral(expr.getIncr())))
				|| (expr.hasIncr() && isDoubleType(expr.getIncr().collectType(stScope, expr)))
				) {
			mType = new MatrixType(new PrimitiveType(TYPENAME_DOUBLE));
			isInteger = false;
		} else if(isIntLiteral(expr.getLower()) 
				|| isIntegerType(expr.getLower().collectType(stScope, expr))) {
			mType = new MatrixType(new PrimitiveType(TYPENAME_INTEGER));
			isInteger = true;
		} else  {
			if(isIntegerType(expr.getLower().collectType(stScope, expr.getLower()))
					&& (expr.hasIncr() && isIntegerType(expr.getIncr().collectType(stScope, expr.getIncr())))) {
				isInteger = true;				
				mType = new MatrixType(new PrimitiveType(TYPENAME_DOUBLE));
			} else {
				isInteger = false;
				mType = new MatrixType(new PrimitiveType(TYPENAME_INTEGER));
			}
			// TODO: This case is quite series, need list all cases...
		}

		// [1] Literal:  IntLiteralExpr and FPLiteralExpr, NEVER consider StringLiteralExpr
		if(isLiteral(expr.getLower())  && isLiteral(expr.getUpper()) 
				&& ( !expr.hasIncr() || expr.getIncr()==null || isLiteral(expr.getIncr()) ) )  { 
			
			extent = getIntegerExtent(expr, isInteger);
			
			java.util.List<Integer> dim = new ArrayList<Integer>();
			dim.add(extent);
			mType.setSize(new Size(dim));
			
			// Fortran doesn't support float/double in the range expression
			// So transform the range expression
			if(isInteger) {
				// Make super all lower:incr:upper are integer or not 
				if(isIntLiteral(expr.getUpper()) 
				  || isIntegerType(expr.getUpper().collectType(stScope, expr)) )
				{
					// All of three are integer, OK, no need to transform
				} else {
					// A3 = 1:6.3	==> A3 = 1:floor(6.3)
	            	natlab.ast.List<Expr> list = new natlab.ast.List<Expr>();
    				list.add(expr.getUpper());	                				
	            	ParameterizedExpr exprParam = new ParameterizedExpr(new NameExpr(new Name("floor")), list);
	            	expr.setUpper(exprParam);
				}
			} else {
				// transformDoubleRange
				// A2 = -2.5:2.5			==> A2 = (0:(6-1))+(-2.5)
				// A4 = 10:2.5:15			==> A4 = (0:(3-1))*(2.5)+10
				PlusExpr newPlus = new PlusExpr();
				newPlus.setRHS(expr.getLower());
				RangeExpr newRange = new RangeExpr();
				newRange.setLower(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue("0")));
				newRange.setUpper(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue(""+(extent-1))));
				if(expr.hasIncr()) {
					MTimesExpr newMTime = new MTimesExpr();
					newMTime.setLHS(newRange);
					newMTime.setRHS(expr.getIncr());
					newPlus.setLHS(newMTime);
				} else {
					newPlus.setLHS(newRange);
				}
				ASTNode parent = expr.getParent();
				int loc = parent.getIndexOfChild(expr);
				parent.setChild(newPlus, loc);
			}
		} else {
			// [2] extent are variables
			// get value for each one
			SymbolTableEntry varEntry = stScope.getSymbolById(varNode.getVarName());
			int first, last;
			// first = last = ERROR_EXTENT;
			String firstStr,lastStr,incStr;
			firstStr = lastStr = "";
			firstStr = getVariableValue(stScope, ((RangeExpr)expr).getLower());
			lastStr = getVariableValue(stScope, ((RangeExpr)expr).getUpper());
			java.util.List<String> dim = new ArrayList<String>();
			if(isInteger) {
				if(expr.hasIncr()) {
					incStr = getVariableValue(stScope, ((RangeExpr)expr).getIncr());
					// Using integer division, so don't need floor()
					strExtent = "("+lastStr+"-"+firstStr+")/"+incStr+"+1";
				} else if(firstStr!=null && lastStr!=null) {
					strExtent = lastStr+"+1"+"-"+firstStr;
				}
			} else {
				if(expr.hasIncr()) {
					incStr = getVariableValue(stScope, ((RangeExpr)expr).getIncr());
					strExtent = "floor(("+lastStr+"-"+firstStr+")/"+incStr+")+1";
				} else if(firstStr!=null && lastStr!=null) {
					strExtent = "floor("+lastStr+"-"+firstStr+")"+"+1";
				}
			}

			bTransform = false;
			// # 2nd time calling RangeExpr.inferType() should not trigger following transformation
			// Because the transformation will 
			//	- Transform the floating point range expression to integer range expression
			//	- lower, upper, incr will all become Integer
			
			// For integer, only adjust range.upper() when it is not integer expression, 
			if(isInteger && !(isIntegerType(expr.getUpper().collectType(stScope, expr.getUpper()))))  {
				natlab.ast.List<Expr> upperlist = new natlab.ast.List<Expr>();
				upperlist.add(expr.getUpper());	
            	ParameterizedExpr upperParam = new ParameterizedExpr(new NameExpr(new Name("floor")), upperlist);
				expr.setUpper(upperParam);
				bTransform = true;
			}
			// Transform the floating point range expression to integer range expression
			if(!isInteger || bTransform) {
				// Form the extent expression,
				// "floor((Upper-Lower)/Incr)+1";
				MinusExpr newMinus = new MinusExpr();
				newMinus.setLHS(expr.getUpper());
				newMinus.setRHS(expr.getLower());
				
				natlab.ast.List<Expr> list = new natlab.ast.List<Expr>();
				if(expr.hasIncr()) {
					MDivExpr newDiv = new MDivExpr();
					newDiv.setLHS(newMinus);
					newDiv.setRHS(expr.getIncr());
					list.add(newDiv);
				} else {
					list.add(newMinus);
				}
            	PlusExpr newPlus = new PlusExpr();
				if(!isInteger) {
					// Need another floor() function for the division
	            	ParameterizedExpr exprParam = new ParameterizedExpr(new NameExpr(new Name("floor")), list);
	            	newPlus.setLHS(exprParam);
				} else {
					// Integer don't need another floor() function, because it's integer division
					newPlus.setLHS(list.getChild(0));
				}
            	newPlus.setRHS(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
            	
            	// (1) Create new variable = this new parameterized-expression            	
            	NameExpr newVar = addNewAssignment(newPlus, expr, null, stScope);

            	// using that temporary assignment 
            	strExtent = newVar.getName().getID();
            	
            	// Re-build this range expression
				RangeExpr newRange = new RangeExpr();
				
				if(isInteger) {
					// A3 = 1:6.3	==> A3 = 1:floor(6.3)
					// already did above.
				} else {
					// A2 = -2.5:2.5			==> A2 = (0:(6-1))+(-2.5)
					// A4 = 10:2.5:15			==> A4 = (0:(3-1))*(2.5)+10
					newRange.setLower(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue("0")));
			
					MinusExpr lowMinus = new MinusExpr();
					lowMinus.setLHS(newVar);
					lowMinus.setRHS(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));				
	            	newRange.setUpper(lowMinus);
	
	            	PlusExpr lowerPlus = new PlusExpr();
					lowerPlus.setRHS(expr.getLower());				
					
					ASTNode parent = expr.getParent();
					int loc = parent.getIndexOfChild(expr);
					
					// ForStmt's index variable
	            	Stmt stmtNode = ASTToolBox.getParentStmtNode(expr);
	            	if(stmtNode.getParent() instanceof ForStmt) {
	            		List<Stmt> stmtList = ((ForStmt) stmtNode.getParent()).getStmtList();
	        			// (2) Replace by another temporary variable
	        			// Create new assignment and replace only one
	        			RangeExpr dupRange = new RangeExpr();
	        			dupRange.setUpper(newRange.getUpper());
	        			dupRange.setLower(newRange.getLower());
	        			if(newRange.hasIncr()) {
	        				dupRange.setIncr(newRange.getIncr());
	        			}	        			
	        			
	                	NameExpr newVar2 = addNewAssignment(dupRange, stmtNode, stmtNode.getParent(), stScope);
	                	// set flag: this range-expr is not used by ForStmt

						// Using new variable to create new assignment
						if(expr.hasIncr()) {
							MTimesExpr newMTime = new MTimesExpr();
							newMTime.setLHS(expr.getIncr());	// put scalar on LHS makes MTimesExpr infer faster
							newMTime.setRHS(newVar2);
							lowerPlus.setLHS(newMTime);
						} else {
							lowerPlus.setLHS(newVar2);
						}
						
	        			// (3) Need move to this assignment to inside ForStmt
	        			stmtList.insertChild(stmtNode, 0);
	        			stmtNode.setParent(stmtList);

	        			loc = stmtNode.getIndexOfChild(expr);
						stmtNode.setChild(lowerPlus, loc);
						stmtList.generateUseBoxesList();

			    		return new PrimitiveType(TYPENAME_DOUBLE);
						
						
	            	} else {
						if(expr.hasIncr()) {
							MTimesExpr newMTime = new MTimesExpr();
							newMTime.setLHS(expr.getIncr());	// put scalar on LHS makes MTimesExpr infer faster
							newMTime.setRHS(newRange);
							lowerPlus.setLHS(newMTime);
						} else {
							lowerPlus.setLHS(newRange);
						}

						parent.setChild(lowerPlus, loc);	            		
	            	}
				}
			}
			// Using the new extent 
			dim.add(strExtent);
			Size s = new Size();
			s.setDynamicDims(dim);
			mType.setSize(s);					
		}

    			

    	if(bForStmtIndex) {
    		return new PrimitiveType(TYPENAME_INTEGER);
    	} else { 
    		return mType;
    	}
	}
	
	// Two way to add the new assignment
	// Parameters: 
	//	- parentNode: is optional
	// 		1. parentNode!=null: setChild(), replace old one in current node (curStmt),
	// 		2. parentNode==null: find the upper stmt-list, add to it
	public static NameExpr addNewAssignment(Expr RHS, ASTNode curStmt, 
			ASTNode parentNode, SymbolTableScope stScope) {
		return addNewAssignment(RHS, curStmt, parentNode, stScope, new PrimitiveType(TYPENAME_INTEGER));
	}
	public static NameExpr addNewAssignment(Expr RHS, ASTNode curStmt, 
			ASTNode parentNode, SymbolTableScope stScope, Type pType) {
		
		if(RHS==null || curStmt==null)
			return null;

		// Find the parent statement list node, and directly child node
		// It must be done first, because the new assignment may break
		// the original parent/child relation, 
		// i.g. curStmt becomes child of new assignment
		ASTNode parent = curStmt.getParent(); 
		ASTNode child = curStmt;
		int loc = 0;
		if(parentNode!=null) {
			loc = parentNode.getIndexOfChild(child);
		} else {
			while ((parent!=null) && !(parent instanceof natlab.ast.List)) {
	    		child  = parent;
	    		parent = child.getParent(); 	   
	    	}
			if(parent==null)
				return null;

			loc = parent.getIndexOfChild(child);
		}				
		
		// Create new assignment and add to AST
		String tmpName = getTempVarName(curStmt);
		NameExpr lhs = new NameExpr(new Name(tmpName));
		AssignStmt newAssign = new AssignStmt();
		newAssign.setLHS(lhs);
		newAssign.setRHS(RHS);
		newAssign.generateUseBoxesList();

		// Add the new assignment to location
		if(parentNode!=null) {
			parentNode.setChild(newAssign, loc);
		} else {
			parent.insertChild(newAssign, loc);
		}
		// Add new declaration node
		
    	VariableDecl tmpDecl = new VariableDecl(tmpName, pType, tmpName);
    	tmpDecl.setNodeID();

    	// adding them into tree;
    	while(!(parent.getChild(0) instanceof VariableDecl)) {
    		do {
    			parent=parent.getParent();
    		} while(parent!=null && !(parent instanceof List));
    		if(parent==null)
    			break;
    	}
    	ASTNode root = parent;
    	while(!(root instanceof Script) && !(root instanceof Function)) {
    		root = root.getParent();
    		if(root==null)
    			break;
    	}
    	if(root!=null) {
    		if(root instanceof Script) 
    			((Script) root).addDeclStmt(tmpDecl);
    		if(root instanceof Function) 
    			((Function) root).addDeclStmt(tmpDecl);
    	} else if(parent!=null && (parent.getChild(0) instanceof VariableDecl)) {
	    	System.err.println("[addNewAssignment]"+tmpDecl.getStructureString());
			parent.insertChild(tmpDecl, 0);
			((VariableDecl)parent.getChild(0)).setType(pType);
		} else {
	    	System.err.println("[addNewAssignment]"
	    			+" Cannot find the top level stmt-list to add this "+tmpDecl.getStructureString());
		}
		
		// Add new symbol table entry
		SymbolTableEntry e = new SymbolTableEntry(tmpName, tmpName, newAssign);
		e.setDeclLocation(tmpDecl);
		stScope.addSymbol(e);

		return lhs;
	}
	//-------------------------------------------------------------------------
	// Infer type for intrinsic function, in parameterized form
	// or array variable access
	public static Type inferType(SymbolTableScope stScope, 
			ParameterizedExpr expr, ASTNode varNode)
	{
		gstScope = stScope;		// used by createMatrixType()
		Type varType = null;
		if(!(expr.getTarget() instanceof NameExpr)) {
			System.err.println("[Error]inferType-ParameterizedExpr:"+expr.getStructureString());
			return null;
		}
        SymbolTableEntry stEntry = stScope.getSymbolById(
        		((NameExpr) expr.getTarget()).getName().getID());
    	// [1] This is an variable, this is a use-node, where symbol table should has its type 
        if(stEntry!=null) {
	        expr.isVariable = true;
	    	VariableDecl varDecl = (VariableDecl) stEntry.getDeclLocation();
	    	varType = varDecl.getType();
	    	// According to different case, return different type
	    	// Case 1: A = U(2,3)
	    	// 	Since this an array-access, so it's a single element type
	    	// 	 
	    	// Case 2: A = U(1,:), handle a sub-matrix: U{m,n}, U(1,:)={n}
	    	//
	    	// Case 3: A = U(1,2:n), handle a sub-matrix: U(1,2:n)={2:n}
	    	//
	    	// Case 4: A = U(:,:), A = U(:) ; ==> A=U
	    	//		  translate into the whole matrix:  
	    	if(varType instanceof MatrixType) {
		    	// (1) adjust RHS in case of: 
	        	// When using linear indexing, which means index misses some dimension
	        	// i.g.  A=Matrix(1*5),   expr:  A(2)  => A(1,2) 
	        	// i.g.  B=Matrix(5*1),   expr:  B(3)  => B(3,1)
	        	// i.g.  C=Matrix(2*10),  expr:  C(4)  => B(1,4)	// row major ??
	        	// 							expr:  C(15)  => B(2,5)	// row major
	    		MatrixType mType = createMatrixType((MatrixType)varType);
	    		boolean bSuccess = adjustParameterizedExpr((MatrixType) mType, expr);
	    		// False means from expr cannot get a proper type! 
	    		if(!bSuccess)
	    			return mType;
	    		
	    		// (2) Checking RHS expression type's format - lType
	    		PrimitiveType pType = (PrimitiveType) ((MatrixType) varType).getElementType();
	        	
	        	
	        	// Get the type of what the expression means
	        	varType = getParameterizedExprType(stScope, ((MatrixType) varDecl.getType()), (ParameterizedExpr) expr);

	    	} else if(isCharacterType(varType)) {
	    		int cnt = 0;
	    		for(Expr args:expr.getArgs()) {
	    			cnt ++;
	    		}
	    		if(cnt==1) {
	    			varType = new PrimitiveType("character(1)");
	    		}
		    	// TODO: ch=str(1:3), ch should according to the range of str(3), advance feature
	    	}
	    	
        } else {
        	// [2] This is a function
    		expr.isVariable = false;

	        // List<Expr> args = expr.getArgs();
            String fname = ((NameExpr)expr.getTarget()).getName().getID();

            // TODO: following may be need union-type, ...
            // For MATLAB built-in type conversion function
            // <<MATLAB-Programming 2-30>>
            if(fname.equalsIgnoreCase("int8")
            		|| fname.equalsIgnoreCase("uint8")
            		|| fname.equalsIgnoreCase("int16")
            		|| fname.equalsIgnoreCase("uint16")
            		|| fname.equalsIgnoreCase("int32")
            		|| fname.equalsIgnoreCase("uint32")
            		|| fname.equalsIgnoreCase("int64")
            		|| fname.equalsIgnoreCase("uint64")
            		|| fname.equalsIgnoreCase(TYPENAME_FLOAT)
            		|| fname.equalsIgnoreCase(TYPENAME_DOUBLE)
            		|| fname.equalsIgnoreCase(TYPENAME_LOGICAL)
            		|| fname.equalsIgnoreCase(TYPENAME_COMPLEX)
            		) { 
    			varType =  new PrimitiveType(fname);
    			
            } else if(fname.equalsIgnoreCase("toc")) {
    			varType = new PrimitiveType(TYPENAME_DOUBLE);
    			
            } else if(fname.equalsIgnoreCase("char")) {
    			// TODO: char() is quite complicated
    			
            } else if(fname.equalsIgnoreCase("randn") || fname.equalsIgnoreCase("rand")) {
            	if(expr.getNumArg()==0) {
            		varType = new PrimitiveType(TYPENAME_DOUBLE);
            	} else {
            		varType = createMatrixType(TYPENAME_DOUBLE, expr, true);
            	}
            } else if(fname.equalsIgnoreCase("zeros") 
            		|| fname.equalsIgnoreCase("ones")
            		|| fname.equalsIgnoreCase("magic")) {
            	// initial as integer, can be upgraded later
    			varType = createMatrixType(TYPENAME_INTEGER, expr, true);
    			
            } else if(fname.equalsIgnoreCase("reshape")) {
            	// type could be int/double, union?
            	natlab.ast.List<Expr> list = new natlab.ast.List<Expr>();
            	list.add(expr.getArg(1));	list.add(expr.getArg(2));
            	// It should be a matrix type ...
            	String baseTypeName = TYPENAME_DOUBLE; 
            	Type orgType = (expr.getArg(0).collectType(stScope, varNode));
            	if(isValidType(orgType))
            		baseTypeName = orgType.getName();
            	ParameterizedExpr exprSize = new ParameterizedExpr(expr.getArg(0), list);
    			varType = createMatrixType(baseTypeName, exprSize);
    			
            } else if(fname.equalsIgnoreCase("transpose")) {	 
    			Type opType = expr.getArg(0).collectType(stScope, varNode);
    			varType = transposeType(opType);
    			
            } else if(fname.equalsIgnoreCase("str2num")) {
            	varType =  new PrimitiveType(TYPENAME_DOUBLE);
            	
            } else if(fname.equalsIgnoreCase("num2str")) {
            	varType =  new PrimitiveType("character(50)");
            	//  +(getValue().length()+1) ?? 

            } else if(fname.equalsIgnoreCase("sin") 
            		|| fname.equalsIgnoreCase("cos") 
            		|| fname.equalsIgnoreCase("log") 
            		|| fname.equalsIgnoreCase("log2") 
            		|| fname.equalsIgnoreCase("log10") 
            		) { 
    			varType = expr.getArg(0).collectType(stScope, varNode);
            
            } else if(fname.equalsIgnoreCase("abs")) {	
    			// If a is an integer or real value, the value of the result is | a |; if a is a complex
    			// value (X, Y), the result is the real value SQRT (X**2 + Y**2).    			
    			varType = expr.getArg(0).collectType(stScope, varNode);
    			if(isComplexType(varType)) {
    				varType =  new PrimitiveType(TYPENAME_DOUBLE);	
    			}

            } else if(fname.equalsIgnoreCase(PHI_FUNC_NAME)) {	
    			// TODO: create union type for PHI function, abandoned now!
            	for(int i=0; i<expr.getNumArg(); i++) {
	    			Type argType = expr.getArg(i).collectType(stScope, varNode);
	            	if(argType instanceof MatrixType) {
	            		varType = argType;
	            	}
            	}
            	// some Fortran functions for retrieving command line arguments
            } else if(fname.equalsIgnoreCase("IARGC")	//GETARG
            		|| fname.equalsIgnoreCase("nargin") ) { 
    			varType = new PrimitiveType(TYPENAME_INTEGER);
           	
    			
            } else if(fname.equalsIgnoreCase("clock")) {
            	// Transformation happen in the AssignStmt.getFortran()
    			varType = createMatrixType(TYPENAME_DOUBLE, 1,6);
    			
            } else if(fname.equalsIgnoreCase("min")) {
            	// Only support, MIN(A): if A is a vector, then flat it. MIN(A(1), A(2),...)
            	varType = null;
            	Expr argExpr = expr.getArg(0);
            	if(argExpr instanceof NameExpr) {	// First argument is a variable, 
	            	String arg0 = ((NameExpr)expr.getArg(0)).getName().getID();
		    		SymbolTableEntry stEntry2 = stScope.getSymbolById(arg0);
		    		if(stEntry2!=null) {
			    		Type argType = ((VariableDecl) stEntry2.getDeclLocation()).getType();
			    		varType =  new PrimitiveType(argType.getName());
			    		
        	    		// When only one vector argument, then flat vector
			    		if(argType instanceof MatrixType && expr.getNumArg()==1) {
	    	            	natlab.ast.List<Expr> listMin = new natlab.ast.List<Expr>();
	            	    	java.util.List<Integer> argDims = ((MatrixType) argType).getSize().getDims();
	            	    	if(argDims.size()!=1) {
	        	            	System.err.println("[inferType]MIN(A)["+expr.getStructureString()+"] is not a vector!");
	            	    	} else {
	            	    		for(int i=0; i<argDims.get(0); ++i) {
	            	            	natlab.ast.List<Expr> list = new natlab.ast.List<Expr>();
	                				list.add(new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue(""+(i+1))));	                				
	            	            	ParameterizedExpr exprSizeFunc = new ParameterizedExpr(argExpr , list);
	            	            	listMin.add(exprSizeFunc);
	            	    		}
	            	    		expr.setArgList(listMin);
	            	    	}
			    		}
		    		}
            	}
	    		if(varType == null) {
	    			varType = new PrimitiveType(TYPENAME_DOUBLE);
	    		}
	    		
            } else if(fname.equalsIgnoreCase("sqrt")) {
    			varType = new PrimitiveType(TYPENAME_DOUBLE);
    			transformIntegerArg2Double(stScope, expr, 1);    			
            	
            } else if(fname.equalsIgnoreCase("mod") || fname.equalsIgnoreCase("rem")) {
            	// Actually it accept the matrix in MATLAB
            	boolean isMatrix = false;
            	boolean isDouble = false;
            	// find the double type
            	for(Expr arg: expr.getArgs()) {
            		if(isFPLiteral(arg)) {
            			isDouble = true;
            			break;
            		} else if(isIntLiteral(arg)) {
            		} else {
            			Type argType = arg.collectType(stScope, varNode);
            			if(argType instanceof MatrixType) {
            				isMatrix = true;
            				varType = argType;
            				break;
            			} else if(isDoubleType(argType)) {
                			isDouble = true;
                			break;
            			}
            		}            		
            	}
            	if(isMatrix) {
            		// using that argument's type
            	} else if(isDouble) {
        			varType = new PrimitiveType(TYPENAME_DOUBLE);
        			// convert integers to doubles
        			transformIntegerArg2Double(stScope, expr, 2);
            	} else {
            		varType = new PrimitiveType(TYPENAME_INTEGER);
            	}

            } else if(fname.equalsIgnoreCase("round") || fname.equalsIgnoreCase("floor")
            		|| fname.equalsIgnoreCase("fix")) {
            	expr.setTarget(new NameExpr(new Name("floor")));
    			varType = new PrimitiveType(TYPENAME_INTEGER);
    			transformIntegerArg2Double(stScope, expr, 1);
    			
            } else if(fname.equalsIgnoreCase("ceil")) {
    			varType = new PrimitiveType(TYPENAME_INTEGER);
    			transformIntegerArg2Double(stScope, expr, 1);
    			
    			
            } else if(fname.equalsIgnoreCase("mean")) {
    			varType = new PrimitiveType(TYPENAME_DOUBLE);
    	        for(Expr arg : expr.getArgs()) {
    	        	if(arg instanceof NameExpr) {
    	            	String arg0 = ((NameExpr)arg).getName().getID();
    		    		SymbolTableEntry stEntry2 = stScope.getSymbolById(arg0);
    		    		if(stEntry2!=null) {
    			    		Type argType = ((VariableDecl) stEntry2.getDeclLocation()).getType();
    			    		if(argType instanceof MatrixType) {
    			    			varType = createMatrixType(TYPENAME_DOUBLE, (MatrixType)argType);
    			    			varType = getTrimedMatrixType((MatrixType)varType, 1);
    			    		}
    		    		}
    	        	} else if(arg instanceof ParameterizedExpr) {
    	        		// Assume it's arg=F(:) 
    	    			varType = new PrimitiveType(TYPENAME_DOUBLE);
    	        	}
    	            break;	// assume it has only one argument
    	        }
            } else if(fname.equalsIgnoreCase("exp")) {
    			varType = new PrimitiveType(TYPENAME_DOUBLE);
            } else if(fname.equalsIgnoreCase("sum")) {
    			varType = new PrimitiveType(TYPENAME_DOUBLE);
    				
            } else if(fname.equalsIgnoreCase("numel")
            		|| (fname.equalsIgnoreCase("size"))) {
            	// Transform the function into corresponding Fortran functions 
            	// for first element is character(), then transform into LEN()
            	// Otherwise, numel(A) will be transformed into SIZE(a,1)*SIZE(a,2)...
            	String arg0 = ((NameExpr)expr.getArg(0)).getName().getID();
	    		SymbolTableEntry stEntry2 = stScope.getSymbolById(arg0);
	    		if(stEntry2!=null) {
		    		Type argType = ((VariableDecl) stEntry2.getDeclLocation()).getType();
	            	if(isCharacterType(argType)) {
	            		transform2ParameterizedExpr(expr, new NameExpr(new Name("LEN")), expr.getArg(0));
	            	} else if(argType instanceof MatrixType) {
	            		// MATLAB size(Array,Dim) is same as Fortran
	            		// Convert NUMEL(Array) to SIZE(Array)...
	            		if(fname.equalsIgnoreCase("numel")) {
	            	    	java.util.List argDims = null;
	            	    	java.util.List<Integer> argIntDims = ((MatrixType) argType).getSize().getDims();
	            	    	java.util.List<String> argStrDims = ((MatrixType) argType).getSize().getDynamicDims();
	            	    	if(argIntDims==null) {
	            	    		argDims = argStrDims;
	            	    	} else {
	            	    		argDims = argIntDims;
	            	    	}
        	            	NameExpr funcNameExpr = new NameExpr(new Name("SIZE"));
            	    		MTimesExpr mainExpr = null; 
    	        			ASTNode parent = expr.getParent();
        	    			int loc = parent.getIndexOfChild(expr);
        	    			if(argDims!=null) {
	            	    		for(int i=0; i<argDims.size(); ++i) {
	            	            	natlab.ast.List<Expr> list = new natlab.ast.List<Expr>();
	            	            	list.add(expr.getArg(0));
	                				list.add(new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue(""+(i+1))));                				
	            	            	ParameterizedExpr exprSizeFunc = new ParameterizedExpr(funcNameExpr , list);
	            	    			if(i==0) {
	            	    				mainExpr = new MTimesExpr();
	            	    				mainExpr.setChild(exprSizeFunc, i);
	            	    			} else if(i==1) {
	            	    				mainExpr.setChild(exprSizeFunc, i);
	            	    			} else {
	            	    				MTimesExpr extraExpr = new MTimesExpr();
	            	    				extraExpr.setChild(mainExpr, 0);
	            	    				extraExpr.setChild(exprSizeFunc, 1);
	            	    				mainExpr = extraExpr;
	            	    			}
	                	    		if(argDims.size()==1) {
	                	    			parent.setChild(exprSizeFunc, loc);
	                	    		}
	            	    		}
	            	    		if(argDims.size()>1 && mainExpr!=null) {
	            	    			parent.setChild(mainExpr, loc);
	            	    		}
        	    			} else {
        	    				System.err.println("[inferType] NUMEL(Array), cannot know the type of Array "+expr.getStructureString());
        	    			}
	            		}
	            	}
	    		}
            	varType =  new PrimitiveType(TYPENAME_INTEGER);
            } else if(fname.equalsIgnoreCase("bitand")) {
            	varType = new PrimitiveType(TYPENAME_INTEGER);

            } else {	
            	// TODO: Handle functions 
            	// 1. the built-in/intrinsic functions
            	// 2. User-defined functions
            	varType = createFunctionSignature(stScope, expr, varNode);
            	// OR return first argument's type, abs(x), ...
    			// varType = expr.getArg(0).collectType(stScope, varNode);
            }
        }
		return varType;
	}
	
	//-------------------------------------------------------------------------
	// Infer type for intrinsic functions in command form
	public static Type inferTypeIntrinsicFunction(NameExpr node) {
		Type varType = null;
		String varName = node.getName().getID();
		if(varName.equalsIgnoreCase("toc")) {
			cmdFormFuncList.add(node);
			varType = new PrimitiveType(TYPENAME_DOUBLE);
		} else if(varName.equalsIgnoreCase("clock")) {
			cmdFormFuncList.add(node);
			varType = createMatrixType(TYPENAME_DOUBLE, 1,6);
		}
		return varType;
	}
	//-------------------------------------------------------------------------
	
	public static boolean isValidType(Type varType)
	{
		return (varType!=null && !(varType instanceof UnknownType));
	}
	public static boolean isIntegerType(Type varType) {
        if(varType!=null && varType instanceof PrimitiveType
        		&& varType.getName().equalsIgnoreCase(TYPENAME_INTEGER)) {
        	return true;
        } else {
        	return false;
        }
	}
	 
	public static String getLiteralString(LiteralExpr expr) {
		if(expr==null) {
			return null;
		} else if(isIntLiteral(expr)) {
			return ""+getIntLiteralValue(expr);
		} else if(isFPLiteral(expr)) {
			return ""+getFPLiteralValue(expr);
		} else {
			return ((StringLiteralExpr) expr).getValue();
		}
	}
	public static int getIntLiteralValue(Expr expr) {
		if(isIntLiteral(expr)) {
	        if(expr instanceof IntLiteralExpr) {
	        	return ((IntLiteralExpr) expr).getValue().getValue().intValue();
	        } else if (expr instanceof UMinusExpr) {
	        	return -((IntLiteralExpr) ((UMinusExpr) expr).getOperand()).getValue().getValue().intValue();
	        } else if (expr instanceof UPlusExpr) {
	        	return ((IntLiteralExpr) ((UPlusExpr) expr).getOperand()).getValue().getValue().intValue();
	        }
		} 
		return 0;
	}
	public static double getFPLiteralValue(Expr expr) {
		if(isFPLiteral(expr)) {
	        if(expr instanceof FPLiteralExpr) {
	        	return ((FPLiteralExpr) expr).getValue().getValue().doubleValue();
	        } else if (expr instanceof UMinusExpr) {
	        	return -((FPLiteralExpr) ((UMinusExpr) expr).getOperand()).getValue().getValue().doubleValue();
	        } else if (expr instanceof UPlusExpr) {
	        	return ((FPLiteralExpr) ((UPlusExpr) expr).getOperand()).getValue().getValue().doubleValue();
	        }
		} 
		return 0.0;
	}
	public static boolean isLiteral(Expr expr) {
        if((expr instanceof LiteralExpr) 
	         || ((expr instanceof UMinusExpr) && ((UMinusExpr) expr).getOperand() instanceof LiteralExpr ) 
	         || ((expr instanceof UPlusExpr) && ((UPlusExpr) expr).getOperand() instanceof LiteralExpr ) 
			){ 
           	return true;
        } else {
        	return false;
        }
	}
	public static boolean isIntLiteral(Expr expr) {
        if((expr instanceof IntLiteralExpr) 
	         || ((expr instanceof UMinusExpr) && ((UMinusExpr) expr).getOperand() instanceof IntLiteralExpr ) 
	         || ((expr instanceof UPlusExpr) && ((UPlusExpr) expr).getOperand() instanceof IntLiteralExpr ) 
			){ 
           	return true;
        } else {
        	return false;
        }
	}
	public static boolean isFPLiteral(Expr expr) {
        if((expr instanceof FPLiteralExpr) 
	         || ((expr instanceof UMinusExpr) && ((UMinusExpr) expr).getOperand() instanceof FPLiteralExpr ) 
	         || ((expr instanceof UPlusExpr) && ((UPlusExpr) expr).getOperand() instanceof FPLiteralExpr ) 
			){ 
           	return true;
        } else {
        	return false;
        }
	}
	public static boolean isStringLiteral(Expr expr) {
		return (expr instanceof StringLiteralExpr);
	}

	public static boolean isComplexType(Type varType) {
        if(varType!=null && varType instanceof PrimitiveType
        		&& varType.getName().equalsIgnoreCase(TYPENAME_COMPLEX)) {
        	return true;
        } else {
        	return false;
        }
	}
	public static boolean isDoubleType(Type varType) {
        if(varType!=null && varType instanceof PrimitiveType
        		&& varType.getName().equalsIgnoreCase(TYPENAME_DOUBLE)) {
        	return true;
        } else {
        	return false;
        }
	}
	
	public static boolean isCharacterType(Type varType) {
        if(varType!=null && varType.getName().length()>=TYPENAME_CHARACTER.length()
				&& varType.getName().substring(0, TYPENAME_CHARACTER.length()).equalsIgnoreCase(TYPENAME_CHARACTER)) {
        	return true;
        } else {
        	return false;
        }
	}
	public static boolean isLogicalType(Type varType) {
		if(varType!=null 
				&& varType instanceof PrimitiveType 
				&& varType.getName().equalsIgnoreCase(TYPENAME_LOGICAL)) {
			return true;
		} else {
			return false;
		}
	}

	private static Integer maxInteger(Integer i1, Integer i2) {
		return (i1>=i2?i1:i2);
	}

    // Special case,  
    // 	U(1:n, j1) = tmp;  U={n,m},  tmp={n,1}
    // MATLAB legal, but Fortran needs RHS to be tmp(:,1);
	// So based on the LHS expression's type, adjust RHS expression
	public static Type adjustParamAssignment(ParameterizedExpr expr, 
			MatrixType varType, Expr rhs, Type rType) {
		if(varType==null) 
			return null;
		String typeName =  varType.getName();
		// LHS type, based on the param-expr
		Type expType = getParameterizedExprType(gstScope, varType, expr);
		
    	
/*    	
		MatrixType lType = createMatrixType(typeName, (ParameterizedExpr) expr);
 [lType] {int}MatrixType [[1:n, m]]
 
 [lhsType] {int}MatrixType [[1:n, m]]
 [varType] {int}MatrixType [[n, m]]

TODO: rhsType ==> [n-1+1]
 [rhsType] {int}MatrixType [[n, 1]]
 [expType] {int}MatrixType [[n-1+1]]
 
 Case 1:
 	U(i,j)=5, 
*/
    	// [2] Check whether expType == rhsType
    	if(!isEqualType(rType, expType)) {
    		// currently only handle this special case
    		if(rType instanceof MatrixType && expType instanceof MatrixType ) {
    			int dimRHS = getDimensionNumber((MatrixType)rType);
    			int dimExp = getDimensionNumber((MatrixType)expType);
    			if(dimRHS>dimExp) {
    				if(rhs instanceof NameExpr) {
    					adjustArrayIndex((MatrixType)expType, (MatrixType)rType, (NameExpr)rhs);
    				}
    			}
    		} else if(rType instanceof MatrixType && expType instanceof PrimitiveType ) {
    			// 	There is a case that LHS = primary type, RHS=matrix type
    			if(rhs instanceof NameExpr) {
    				adjustArrayIndex((PrimitiveType) expType, (MatrixType)rType, (NameExpr)rhs);
    			} else if(rhs instanceof BinaryExpr) {
    				adjustArrayIndex((PrimitiveType) expType, (MatrixType)rType, (BinaryExpr)rhs);
    			} else {
    				System.err.println("[adjustParamAssignment] cannot transform ["+rhs+"]from["+rType+"]to["+expType+"]");    				
    			}
    		}
    	}
    	
    	return expType;
	}
	// 
	// Get the type of the parameterized-expression represents.
	// i.g. U={int}{n,m},  U(1,2)=U(1,j)=U(i,2)={int}; 
	//		U(:,2)=U(:,j)={n};   U(i,:)=U(1,:)={m};
	// Parameters:
	//	- varType: the type of the variable, i.g. U={int}{n,m}
	//	- expr  : the ParameterizedExpr expression
	//	- lType : the expression's maximum possible type according to its format,
	//			: their dimensions, i.g. U(i,:)={i,:}
	
	public static Type getParameterizedExprType(SymbolTableScope stScope, MatrixType varType, ParameterizedExpr expr) {
		PrimitiveType pType = (PrimitiveType) varType.getElementType();
		Type resultType = pType;

    	MatrixType lType = createMatrixType(pType.getName(), (ParameterizedExpr) expr, false, false);
    	
    	// Expression has dynamic type, go checking ':'; 
    	// assumption: varType has same number of dimensions as lType	        	
    	if(lType.getSize().getDims()==null && 
    			lType.getSize().getDynamicDims()!=null) {
			java.util.List<String> strDims = lType.getSize().getDynamicDims();

			java.util.List<Integer> viDims = ((MatrixType) varType).getSize().getDims();
			java.util.List<String>  vsDims = ((MatrixType) varType).getSize().getDynamicDims();
			java.util.List<Integer> miDims = new ArrayList<Integer>();
			java.util.List<String>  msDims = new ArrayList<String>();
				    			
			// Type of Symbol table has explicit dimension  
			for(int i=0; i<strDims.size();i++) {
				if(strDims.get(i).equals(":")) {
    				//  U{m,n}, U(1,:)={n}
					if(viDims!=null) {
						miDims.add(viDims.get(i));
    					msDims.add(viDims.get(i).toString());
					} else {
    					msDims.add(vsDims.get(i));
					}
				} else if(strDims.get(i).contains(":")) {
					// U(1,2:n)={2:n} = {n-2}
					viDims = null;
					miDims.clear();
					msDims.add(getStringExtent(stScope, strDims.get(i), true));
				}
			}
			if(miDims.size()>0 || msDims.size()>0) {
				MatrixType mType = new MatrixType(pType);
				Size msize = new Size();
				if(miDims.size()>0) {
    				msize.setDims(miDims);
				} else {
    				msize.setDynamicDims(msDims);
				}
				mType.setSize(msize);
				resultType = mType;
			} else {
				resultType = pType;
			}	    			
    	}
    	return resultType;
	}
	//----------------------------------------------------------------------------------
    // Check and decide the final matrix type for variable like: X(10) = ..., X(1,1)=..
	// varType: the inferred type of the expression, based on RHS
	// expr : the LHS expression, which may indicate a use of the variable, 
	// There are many cases:
	//	- varType = PrimaryType			expr = x(1,2)		: enlarge
	//	- Matrix(2,3)					expr = x(4,5)		: enlarge
	//  - Matrix(3)						expr = x(1,2)		: enlarge ->1*3
	//  - Matrix(1,3)					expr = x(2)			: change expr=x(1,2)
	// ... ...
	// ... ...	
	// Adjust the inferred type (varType) with the matrix-access expression (expr)
	// Get the correct data type that based on those two.
	// Because they may different, i.g. A(1,2)=0.0
	public static MatrixType adjustMatrixType(Type varType, ParameterizedExpr expr) {
		String typeName =  TYPENAME_DOUBLE;
    	if(varType!=null) {
    		typeName = varType.getName();
    	}
    	MatrixType lType = createMatrixType(typeName, (ParameterizedExpr) expr);
    	MatrixType mType = mergeMatrixType(varType, lType);
    	
    	return mType;
	}

	public static void fillStringDimensions(MatrixType lType) {
    	java.util.List<String> lstrDims = lType.getSize().getDynamicDims();
    	if(lstrDims==null) {
        	java.util.List<Integer> lDims = lType.getSize().getDims();
			if(lDims!=null) {
    			java.util.List<String> strDims = new ArrayList<String>();
    			for(Integer dim : lDims) {
    				strDims.add(dim.toString());
    			}
    			lType.getSize().setDynamicDims(strDims);
			}
    	}
	}
	// Convert static dimension to string, so that they can compare each other
	public static void fillStringDimensions(MatrixType lType, MatrixType rType) {
    	java.util.List<Integer> lDims = lType.getSize().getDims();
    	java.util.List<Integer> rDims = rType.getSize().getDims();
		if(lDims==null || rDims==null) {
			if(lDims!=null) {
    			java.util.List<String> strDims = new ArrayList<String>();
    			for(Integer dim : lDims) {
    				strDims.add(dim.toString());
    			}
    			lType.getSize().setDynamicDims(strDims);
			}
			if(rDims!=null) {
    			java.util.List<String> strDims = new ArrayList<String>();
    			for(Integer dim : rDims) {
    				strDims.add(dim.toString());
    			}
    			rType.getSize().setDynamicDims(strDims);
			}
		}
	}

	// The result type will be a matrix type with largest dimension of those two types
	// And the primary type will be the smallest compatible type of those two.
	// Case 1: Primitive-Type, Matrix-Type
	//			- merge primitive type, use the Size of Matrix-Type
	// Case 2: 
	public static MatrixType mergeMatrixType(Type varType, MatrixType lType) {
		String typeName = TYPENAME_DOUBLE;
    	if(varType==null) {
    		return lType;
    	} else {
    		// Get the compatible primitive data type
    		PrimitiveType pType = mergePrimitiveType(varType, new PrimitiveType(lType.getName()));
    		typeName = pType.getName();
    	}

    	MatrixType mType = new MatrixType(new PrimitiveType(typeName));
    	java.util.List<Integer> vDims;  
		if(varType instanceof MatrixType) {
			vDims = ((MatrixType) varType).getSize().getDims();
		} else {
			// use the dimension info of matrix type, use above merged primitive type
			mType = createMatrixType(typeName, lType);
			return mType;
		}
    	
    	java.util.List<Integer> lDims = lType.getSize().getDims();
    	java.util.List<Integer> mDims = new ArrayList<Integer>();
		if(lDims==null || vDims==null) {
			// Dynamic indexes
			// Convert static dimension to string, so that they can compare each other
			fillStringDimensions(lType, (MatrixType)varType);
		/**  // -JL 2009.02.21
			if(lDims!=null) {
    			java.util.List<String> strDims = new ArrayList<String>();
    			for(Integer dim : lDims) {
    				strDims.add(dim.toString());
    			}
    			lType.getSize().setDynamicDims(strDims);
			}
			if(vDims!=null) {
    			java.util.List<String> strDims = new ArrayList<String>();
    			for(Integer dim : vDims) {
    				strDims.add(dim.toString());
    			}
    			((MatrixType)varType).getSize().setDynamicDims(strDims);
			}
		*/
			java.util.List<String> vstrDims = ((MatrixType)varType).getSize().getDynamicDims();
			java.util.List<String> lstrDims = lType.getSize().getDynamicDims();
			java.util.List<String> mstrDims = new ArrayList<String>();
			java.util.List<String> tmpDims = new ArrayList<String>();
			// Align the dimensions
			if(vstrDims.size()>lstrDims.size() && lstrDims.size()>0) {
				for(int i=0;i<vstrDims.size();++i) {
					String strv = vstrDims.get(i); 
					if(strv.equals("1")) {
						tmpDims.add(strv);
					}
					if(i<lstrDims.size())
						tmpDims.add(lstrDims.get(i));
				}
				lstrDims = tmpDims;
			} else if(vstrDims.size()<lstrDims.size() && vstrDims.size()>0) { 
				for(int i=0;i<lstrDims.size();++i) {
					String strl = lstrDims.get(i); 
					if(strl.equals("1")) {
						tmpDims.add(strl);
					}
					if(i<vstrDims.size())
						tmpDims.add(vstrDims.get(i));
				}
				vstrDims = tmpDims;
			}
			
			// If their dimensions still cannot match, then choose larger one. 
			if(vstrDims.size()>lstrDims.size()) {
				Size strSize = new Size();
				strSize.setDynamicDims(vstrDims);
				mType.setSize(strSize);
				return mType;
			} else if(vstrDims.size()<lstrDims.size()) { 
				Size strSize = new Size();
				strSize.setDynamicDims(lstrDims);
				mType.setSize(strSize);
				return mType;
			} else {
				// (vstrDims.size()==lstrDims.size()) 
				// compare each dimension's string, choose bigger one
				for(int i=0;i<vstrDims.size();++i) {
					String str = vstrDims.get(i); 
					String strl = lstrDims.get(i); 
					
					if(str==null && strl==null) {
						str = "1";
					} else if(str==null) {
						str = strl;
					} else if(strl==null) {
						// lType is a unsolved dimension variable,  which has null
						// i.g. D(1,null), then ignore this type, 
						return (MatrixType)varType;			
					} else if (!(str.equals(strl))) {
						// First, skip those uncertain value from temporary variables
						if(str.contains(TEMP_VAR_PREFIX)) {
							str = strl;
						} else if(strl.contains(TEMP_VAR_PREFIX)) {
							// using str;
						} else {
							// Compare (i+1,i), (i-1,i), (i+1-1)
							str = StaticFortranTool.selectLargerExpressions(str, strl);
						}
					}
					mstrDims.add(str);
				}
				Size strSize = new Size();
				strSize.setDynamicDims(mstrDims);
				mType.setSize(strSize);
				return mType;
			}
		} else {
			int vsize = vDims.size();
			int lsize = lDims.size();
	    	
	    	if(vDims.size()==lDims.size()) {
	    		for(int i=0; i<vDims.size(); ++i) {
	    			Integer max = maxInteger(vDims.get(i),lDims.get(i));
	    			mDims.add(max);
	    		}
	    		
    		// Otherwise Using the type with lager dimension 
	    	} else if(vDims.size()<lDims.size()) {
	    		mDims.addAll(lDims);
	    	} else {	// vDims.size()>lDims.size()
	    		mDims.addAll(vDims);
	    	}
    		// Need to update all the previous define-node and use-node, 
	    	
			mType.setSize(new Size(mDims));
	        return mType;
		}
	}

	// Since getName() return primitive type name for Matrix, so it accept 
	// parameters of Primitive-Type or MatrixType
	// Get the smallest compatible Primitive-Type of them two
	public static PrimitiveType mergePrimitiveType(Type varType, Type orgType) {
		if(varType==null || orgType==null) {
			if(varType!=null) {
				return new PrimitiveType(varType.getName());
			} else { // if(orgType!=null)  
				return new PrimitiveType(orgType.getName());
			}
		}
		// Now they are not null
	    PrimitiveType pType = null; 
	    if(varType.getName().equalsIgnoreCase(orgType.getName())) {
	    	pType = new PrimitiveType(varType.getName());
	    } else {
	    	if(orgType.getName().equalsIgnoreCase(TYPENAME_COMPLEX)
	    			|| varType.getName().equalsIgnoreCase(TYPENAME_COMPLEX)) {
	    		pType = new  PrimitiveType(TYPENAME_COMPLEX);
	    	} else if(orgType.getName().equalsIgnoreCase(TYPENAME_DOUBLE)
	    			|| varType.getName().equalsIgnoreCase(TYPENAME_DOUBLE)) {
	    		pType = new  PrimitiveType(TYPENAME_DOUBLE);
	    	} else if(varType.getName().equalsIgnoreCase(TYPENAME_INTEGER)
	    			|| orgType.getName().equalsIgnoreCase(TYPENAME_INTEGER)) {
	    		pType = new  PrimitiveType(TYPENAME_INTEGER);
	    	} else if(varType.getName().equalsIgnoreCase(TYPENAME_LOGICAL)
	    			|| orgType.getName().equalsIgnoreCase(TYPENAME_LOGICAL)) {
	    		pType = new  PrimitiveType(TYPENAME_LOGICAL);
	    	} else {
	            if( isCharacterType(varType) && isCharacterType(orgType)) {
					int loc1= varType.getName().indexOf("(");
					int loc2= varType.getName().indexOf(")");					
					int varLen = (new Integer(varType.getName().substring(loc1+1,loc2))).intValue();
					loc1= orgType.getName().indexOf("(");
					loc2= orgType.getName().indexOf(")");					
					int orgLen = (new Integer(orgType.getName().substring(loc1+1,loc2))).intValue();
					int maxLen = (varLen>=orgLen ? varLen:orgLen);
					pType = new PrimitiveType("character("+(maxLen)+")");
	            	
	            } else {
		    		// TODO: special int8 ...
		    		// character type , TYPENAME_CHARACTER
		    		pType = new PrimitiveType("character(50)");
	            }
	    	}
	    }
	    return pType;
	}
	// Merge between two types
	public static Type mergeType(Type varType, Type orgType) {
		if(!isValidType(orgType)) {
			return varType;
		} else if(!isValidType(varType)) {
			return orgType;
		}
		
	    // Rule-1: all PrimitiveType, choose Complex--Double--Int...
	    if(varType instanceof PrimitiveType && (orgType instanceof PrimitiveType)) { 
	    	return mergePrimitiveType(varType, orgType);
	    } else {
			// Rule-2: if one of them is MatrixType, another is PrimitiveType,
			//		  then merge them .
	    	MatrixType mType;
		    if(varType instanceof MatrixType) {
		    	mType = mergeMatrixType(orgType, (MatrixType)varType);
		    } else if(orgType instanceof MatrixType) {
		    	mType = mergeMatrixType(varType, (MatrixType)orgType);
		    } else {
		    	// There maybe (varType instanceof ArgTupleType)
		    	return varType; 		    	
		    }
	    	return mType;
	    }
	}
	//public static PrimitiveType getCompatibleType(PrimitiveType t1, PrimitiveType t2) {	}
	
	// Create a matrix data type for an ParameterizedExpr 
	// Working for rand/randn/zeros/ones
	// Y = rand(n) returns an n-by-n matrix of values derived as described above.
	// Y = rand(m,n) or Y = rand([m n]) returns an m-by-n matrix of the same.
	public static MatrixType createMatrixType(String PrimitiveTypeName, Integer...dimlist) {
		MatrixType mType = new MatrixType(
				new PrimitiveType(PrimitiveTypeName));
		java.util.List<Integer> dim = new ArrayList<Integer>();
		for(Integer value:dimlist) {
			dim.add(value);
		}
		mType.setSize(new Size(dim));
		return mType;		
	}
	
	//-------------------------------------------------------------------------
	// Get the maximum type of the expression, according to its format
	public static MatrixType createMatrixType(String PrimitiveTypeName, ParameterizedExpr expr) {
		return createMatrixType(PrimitiveTypeName, expr, false);
	}
	public static MatrixType createMatrixType(String PrimitiveTypeName, ParameterizedExpr expr, boolean bMxM) {
		return createMatrixType(PrimitiveTypeName, expr, bMxM, true);
	}
	// Parameters:
	// 	- bMxM : flag for special case: i.g. randn(m) => m-by-m matrix
	//	- bGetValue: true: get value of the index variable/expression as result
	//				false: just use the index-variable as result
	public static MatrixType createMatrixType(String PrimitiveTypeName, 
			ParameterizedExpr expr, boolean bMxM, boolean bGetValue) {
        List<Expr> args = (List<Expr>) expr.getArgs();
		MatrixType mType = new MatrixType(
				new PrimitiveType(PrimitiveTypeName));
		java.util.List<Integer> Dims = new ArrayList<Integer>();
		Size mSize = new Size();
		java.util.List<String> strDim = new ArrayList<String>();
		java.util.List<String> strVarDim = new ArrayList<String>();
		
		boolean bDynamic = false;
        for(Expr arg : args) {
        	if(arg instanceof IntLiteralExpr) {
        		// if(((IntLiteralExpr) arg).getValue().getValue().intValue()==1) 
        			// column matrix/row matrix,
        			// currently treat as two dimension array
        			// i.g.: A=1:10, B=rand(1,10); C=rand(10,1)
        		// 
        		int extent = getIntLiteralValue(arg); 
	        	Dims.add(extent);
    			strDim.add(""+extent);
    			strVarDim.add(""+extent);

    			// Special case: randn(m) => m*m
        		if((args.getNumChild()==1) && bMxM) {
		        	Dims.add(extent);
	    			strDim.add(""+extent);
        		}
        		
        	} else if(arg instanceof RangeExpr) {
        		if(bGetValue) {
	        		Expr upper = ((RangeExpr)arg).getUpper();
	        		if(isIntLiteral(upper)) {
	            		int extent = getIntLiteralValue(upper); 
	    	        	Dims.add(extent);
	        			strDim.add(""+extent);
	        			strVarDim.add(""+extent);
	        		} else {
	        			String strValue = ""; 
	        			if(bGetValue) {
	            			strValue = getVariableValue(gstScope, upper);
	        			} else { 
	        				strValue = upper.getStructureString();
	        			}
	        			strDim.add(strValue);
	        			strVarDim.add(upper.getStructureString());
	            		bDynamic = true;
	        		}
        		} else {
    				String strValue = arg.getStructureString();
        			strDim.add(strValue);
        			strVarDim.add(strValue);
            		bDynamic = true;
        		}
        	} else {
	        	// Using variable in the extent of each dimension
        		// TODO: Different type of Node are handled by getVariableValue()
        		String strValue = null;
    			if(bGetValue) {
        			strValue = getVariableValue(gstScope, arg);
    			} else { 
    				strValue = arg.getStructureString();
    			}
        		if(strValue == null) {
        			strValue = arg.getStructureString();
        		}
    			strDim.add(strValue);
    			strVarDim.add(arg.getStructureString());
        		bDynamic = true;
        	}
        }
		if(bDynamic) {
			mSize.setDynamicDims(strDim);
			mSize.setVariableDims(strVarDim);
		} else {
			mSize.setDims(Dims);
		}
		mType.setSize(mSize);
		return mType;		
	}
	// Trim one dimension of the matrix type, 
	// 	- num : the index of the dimension, starting from 1
	public static Type getTrimedMatrixType(MatrixType varType, int num) {
		boolean isPrimitiveType = false;
		MatrixType mType = createMatrixType(varType);
		Size vSize = varType.getSize();
		
		java.util.List<Integer> dims = new ArrayList<Integer>();
		Size mSize = new Size();
		java.util.List<String> strDim = new ArrayList<String>();
		java.util.List<String> strVarDim = new ArrayList<String>();
		
		if(vSize.getDims()!=null) {
			int i=1;
			for(Integer dim:vSize.getDims()) {
				if(i!=num)
					dims.add(dim);
				++i;
			}
			mSize.setDims(dims);
			isPrimitiveType = (dims.size()==0);
		}
		if(vSize.getDynamicDims()!=null) {
			int i=1;
			for(String str:vSize.getDynamicDims()) {
				if(i!=num)
					strDim.add(str);
				++i;
			}
			mSize.setDynamicDims(strDim);
			isPrimitiveType = isPrimitiveType || (strDim.size()==0);
		}
		if(vSize.getVariableDims()!=null) {
			int i=1;
			for(String str:vSize.getVariableDims()) {
				if(i!=num)
					strVarDim.add(str);		
				++i;
			}
			mSize.setVariableDims(strVarDim);
			isPrimitiveType = isPrimitiveType || (strVarDim.size()==0);
		}
		if(isPrimitiveType) {
			return (PrimitiveType) mType.getElementType();
		} else {
			mType.setSize(mSize);
			return mType;		
		}
	}
	public static MatrixType createMatrixType(MatrixType varType) {
		return createMatrixType(((PrimitiveType) varType.getElementType()).getName(), varType);
	}
	public static MatrixType createMatrixType(PrimitiveType pType, MatrixType varType) {
		return createMatrixType(pType.getName(), varType);
	}
	public static MatrixType createMatrixType(String PrimitiveTypeName, MatrixType varType) {
		MatrixType mType = new MatrixType(
				new PrimitiveType(PrimitiveTypeName));
		Size vSize = varType.getSize();
		java.util.List<Integer> dims = new ArrayList<Integer>();
		Size mSize = new Size();
		java.util.List<String> strDim = new ArrayList<String>();
		java.util.List<String> strVarDim = new ArrayList<String>();
		
		if(vSize.getDims()!=null) {
			for(Integer dim:vSize.getDims())
				dims.add(dim);
			mSize.setDims(dims);
		}
		if(vSize.getDynamicDims()!=null) {
			for(String str:vSize.getDynamicDims())
				strDim.add(str);		
			mSize.setDynamicDims(strDim);
		}
		if(vSize.getVariableDims()!=null) {
			for(String str:vSize.getVariableDims())
				strVarDim.add(str);		
			mSize.setVariableDims(strVarDim);
		}
		mType.setSize(mSize);
		return mType;		
	}
	
	
	public static final int COMBINE_ROW = 0;
	public static final int COMBINE_COLUMN = 1;
	public static final int COMBINE_BOTH = -1;
	
	// Combine two matrix together, used by Matrix concatenation
	// TODO: It can support merge row/column ...  
	// Parameters:
	//	- flag: 0: column,  1: row, -1: both
	//	- lType : type of A in [A,B] or [A;B]
	//	- rType : type of B in [A,B] or [A;B]
	// Two cases: 
	//	(1) combine row, [A,B]. every element should same dimension(s), 
	//				then combine all dimensions together
	//	(2) combine column, [A;B], result will be B added to 2nd dimension of A 
	//
	public static MatrixType createMatrixType(String PrimitiveTypeName, 
			MatrixType lType, MatrixType rType, int flag) {
		MatrixType mType = new MatrixType(
				new PrimitiveType(PrimitiveTypeName));
		Size mSize = new Size();
		java.util.List<Integer> mDims = new ArrayList<Integer>();
    	java.util.List<String> mstrDims = new ArrayList<String>();

		java.util.List<Integer> lDims = lType.getSize().getDims();
    	java.util.List<Integer> rDims = rType.getSize().getDims();	

    	java.util.List<String> lstrDims = lType.getSize().getDynamicDims();
    	java.util.List<String> rstrDims = rType.getSize().getDynamicDims();

    	// Case 1: if one of the variable type mType is dynamic,  
    	// 			then adjust other to dynamic, make them comparable
    	// #1. If it's dynamic dimensions,stop 
    	if(lDims!=null && rDims!=null) {
    		// We assume they have same dimensions
			if(flag==COMBINE_ROW) {
				// They should have same dimensions, concatenate the last dimension  (# of column)
				if(lDims.size()==rDims.size()) {				
		    		for(int i=0;i<lDims.size() && i<rDims.size(); i++) {
		    			if(i==lDims.size()-1) {
		    				mDims.add(lDims.get(i)+rDims.get(i));
		    			} else {
		    				mDims.add(lDims.get(i));
		    			}
		    		}
				} else {
					System.err.println("Error: Matrix concatenation (in row) requires matrices have same dimension(s).");
				}
    		} else {	 
				// if(flag==COMBINE_COLUMN) 
    			// concatenate the first dimension (# of row)
    			// reault will be two-dimension,
				int inc = 0;
				if((lDims.size()>2 || lDims.size()<1) 
						|| (rDims.size()>2 || rDims.size()<1)) {
					System.err.println("Error: Matrix concatenation (in column) requires matrices have one or two dimension(s).");
				} else if(lDims.size()==1 && rDims.size()==1) {
					mDims.add(2);
					mDims.add(lDims.get(0));
				} else {
					// 	A={1,2}, B={3,2} [A;B]={4,2}
					if(lDims.size()==rDims.size()) {
						mDims.add(lDims.get(0)+rDims.get(0));
						mDims.add(lDims.get(1));
					} else if(lDims.size()>rDims.size()) {
						mDims.add(lDims.get(0)+1);
						mDims.add(lDims.get(1));
					} else if(lDims.size()<rDims.size()) {
						mDims.add(rDims.get(0)+1);
						mDims.add(rDims.get(1));
					}
				}
    		}
    		mSize.setDims(mDims);
    	} else {
    		// if(lDims==null || rDims==null) 
        	if(lDims!=null) {
        		lstrDims = new ArrayList<String>(); 
            	for(Integer ext: lDims) {
            		lstrDims.add(ext.toString());
            	}
            	lDims = null;
        	}
        	if(rDims!=null) {
        		rstrDims = new ArrayList<String>(); 
            	for(Integer ext: rDims) {
            		rstrDims.add(ext.toString());
            	}
            	rDims = null;
        	}
        	// Only addup specified row/column
    		if(lstrDims.size()==rstrDims.size()) {
				if(flag==COMBINE_ROW)
					flag = lstrDims.size()-1;
	    		for(int i=0;i<lstrDims.size() && i<rstrDims.size(); i++) {
	    			if(i==flag || flag<0)
	    				mstrDims.add(lstrDims.get(i)+"+"+rstrDims.get(i));
	    		}
    		}
			mSize.setDynamicDims(mstrDims);
			// mSize.setVariableDims(mstrVarDims);
    	}
		mType.setSize(mSize);
		return mType;		
	}
	
	
	// In order to tranform array indexing cases. 
	// These set of code handles only those special cases.
	public static boolean transformArrayIndexingParamExpr(MatrixType mType, ParameterizedExpr expr) {
		boolean isArrayIndexing = false;
		Type varType = null;
		// Checking whether there is an array variable in index or not.
		int index = 0;
		for(Expr arg: expr.getArgs()) {
			if(!(arg instanceof ColonExpr)
					&& !(arg instanceof RangeExpr)) {
				varType = getVariableType(gstScope, arg);
				if(varType instanceof MatrixType) {
					isArrayIndexing = true;
					break;
				}
			}
			index++;
		}
		if(!isArrayIndexing)
			return false;
		
		// Now, there is array indexing 
		// [1] When first index is array variable in NameExpr, 
		// and this happens in an assignment 
		if(index==0 && expr.getArg(0) instanceof NameExpr 
				&& (expr.getParent() instanceof AssignStmt)) {
			AssignStmt asgStmt = (AssignStmt)expr.getParent();
			
			// [1]:	kk = (1:n); rr = zeros(n, n); rr(kk)=1.0;
			// rr(kk) has one argument, 
			// Index array kk() contains integer, and are all legal index for rr() 
			if(expr.getNumArg()==1) {
				fillStringDimensions((MatrixType) varType);
				fillStringDimensions(mType);
		    	java.util.List<String> lstrDims = mType.getSize().getDynamicDims();
		    	java.util.List<String> rstrDims = ((MatrixType) varType).getSize().getDynamicDims();
				// Save the location first.
    			ASTNode parent = asgStmt.getParent();
    			int loc = parent.getIndexOfChild(asgStmt);

				// (#) Here is the first part of For-loop, it's common part for all following case
    			// Index Assignment of ForStmt
				ForStmt argFor = new ForStmt();
				AssignStmt idxAsg = new AssignStmt();
				idxAsg.setLHS(new NameExpr(new Name("int_tmpvar")));
				idxAsg.setRHS(new RangeExpr(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")), 
						new Opt<Expr>(), new NameExpr(new Name(rstrDims.get(0)))));
				argFor.setAssignStmt(idxAsg);
				// Statement list of ForStmt
				natlab.ast.List<Stmt> forList = new natlab.ast.List<Stmt>();

				// Case 1: rr(kk) has one argument, and in LHS of assignment statement
				// Index array kk() contains integer, and are all legal index for rr() 
				if(expr == asgStmt.getLHS()) {
					// Case 1-1:	kk = (1:n); rr = zeros(n, n); rr(kk)=1.0;
	    			// If there is linear indexing, 
	    			if(lstrDims.size()==2 && rstrDims.size()==1) {		    			
						// First assignment 
		            	natlab.ast.List<Expr> kklist1 = new natlab.ast.List<Expr>();
		            	kklist1.add(new NameExpr(new Name("int_tmpvar")));
		            	ParameterizedExpr kkParam1 = new ParameterizedExpr(((NameExpr)expr.getArg(0)), kklist1);
		            	MinusExpr minus1 = new MinusExpr(kkParam1, 
		            			new IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
	    				MDivExpr div1 = new MDivExpr(minus1, new NameExpr(new Name(lstrDims.get(1))));
	    				PlusExpr plus1 = new PlusExpr(div1, 
	    						new IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
						AssignStmt bufAsg1 = new AssignStmt();
						bufAsg1.setLHS(new NameExpr(new Name("III")));
						bufAsg1.setRHS(plus1);
						
						// Second assignment 
		            	natlab.ast.List<Expr> kklist2 = new natlab.ast.List<Expr>();
		            	kklist2.add(new NameExpr(new Name("int_tmpvar")));
		            	ParameterizedExpr kkParam2 = new ParameterizedExpr(((NameExpr)expr.getArg(0)), kklist2);
		            	MinusExpr minus2 = new MinusExpr(kkParam2, 
		            			new IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
	    				
		            	natlab.ast.List<Expr> plist2 = new natlab.ast.List<Expr>();
	    				plist2.add(minus2);
	    				plist2.add(new NameExpr(new Name(lstrDims.get(1))));
		            	ParameterizedExpr expr2 = new ParameterizedExpr(new NameExpr(new Name("mod")), plist2);

	    				PlusExpr plus2 = new PlusExpr(expr2, 
	    						new IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
						AssignStmt bufAsg2 = new AssignStmt();
						bufAsg2.setLHS(new NameExpr(new Name("JJJ")));
						bufAsg2.setRHS(plus2);
		            	// add to For statement list
						forList.add(bufAsg1);
						forList.add(bufAsg2);
						
						// Modify current assignment
		            	natlab.ast.List<Expr> plist3 = new natlab.ast.List<Expr>();
	    				plist3.add(new NameExpr(new Name("III")));
	    				plist3.add(new NameExpr(new Name("JJJ")));
		            	expr.setArgList(plist3);	
		            	
						forList.add(asgStmt);
						// form the For statement
						argFor.setStmtList(forList);

						// Add current statement
		    	    	parent.setChild(argFor, loc);
						
		    	    	return true;
		    	    			    	    	
					} else if(lstrDims.size()==1 && rstrDims.size()==1) {
						// Case 1-2:	kk = (1:n); rr = zeros(n); rr(kk)=1.0;
						// Change the original assignment 
		            	natlab.ast.List<Expr> kklist2 = new natlab.ast.List<Expr>();
		            	kklist2.add(new NameExpr(new Name("int_tmpvar")));
		            	ParameterizedExpr kkParam2 = new ParameterizedExpr(((NameExpr)expr.getArg(0)), kklist2);
	    				expr.setArg(kkParam2, 0);
						// Statement list of ForStmt
						forList.add(asgStmt);
						// form the For statement
						argFor.setStmtList(forList);
						// Add current statement
		    	    	parent.setChild(argFor, loc);
		    	    	return true;
					}
					
				} else if(expr == asgStmt.getRHS()) {
					// Case 2: rr(kk) has one argument, and in RHS of assignment statement
					// Case 2-1: C = rr(kk); rr = 5:5:50; kk = [1, 3, 6, 7, 10];
					if(lstrDims.size()==1 && rstrDims.size()==1) {
						// Case 1-2:	kk = (1:n); rr = zeros(n); rr(kk)=1.0;
						// Change the original assignment 
		            	natlab.ast.List<Expr> kklist2 = new natlab.ast.List<Expr>();
		            	kklist2.add(new NameExpr(new Name("int_tmpvar")));
		            	ParameterizedExpr kkParam2 = new ParameterizedExpr(((NameExpr)expr.getArg(0)), kklist2);
	    				expr.setArg(kkParam2, 0);

	    				natlab.ast.List<Expr> plist2 = new natlab.ast.List<Expr>();
	    				plist2.add(new NameExpr(new Name("int_tmpvar")));
		            	ParameterizedExpr exprLHS = new ParameterizedExpr((NameExpr)asgStmt.getLHS(), plist2);
		            	asgStmt.setLHS(exprLHS);
						// Statement list of ForStmt
						forList.add(asgStmt);
						// form the For statement
						argFor.setStmtList(forList);
						// Add current statement
		    	    	parent.setChild(argFor, loc);
		    	    	// Need continus to infer the LHS type
		    	    	return true;
					}
					
				}
			} else if(asgStmt.getRHS()==expr 
					&& asgStmt.getLHS() instanceof NameExpr) { 
				// expr.getNumArg()>1,  (index==0)
				// RHS change is called from inferType(), ln:1192
				// Only handle indexes consist of one Array variable, and several ColonExpr. 				
				boolean isPure = true;
				for(int i=0; i<expr.getNumArg(); i++) {
					if(i!=index) {
						if(!(expr.getArg(i) instanceof ColonExpr)){
							isPure = false;
							break;
						}
					}
				}
				if(isPure) {
					// We assume that rstrDims.size()==2, 
					NameExpr lhs = (NameExpr) asgStmt.getLHS();
					// n=4, R = zeros(n, 3); ii = ones(n, n); 
					// tt=R(ii, :);  ==> tt=(16x3) => ((4x4)x3)
					fillStringDimensions((MatrixType) varType);
					fillStringDimensions(mType);
			    	java.util.List<String> lstrDims = mType.getSize().getDynamicDims();
			    	java.util.List<String> rstrDims = ((MatrixType) varType).getSize().getDynamicDims();
			    	MatrixType combinType = createMatrixType((PrimitiveType)mType.getElementType(), ((MatrixType) varType));
			    	java.util.List<String> cstrDims = combinType.getSize().getDynamicDims();
					for(int i=0; i<expr.getNumArg(); i++) {
						if(i!=index) {
					    	cstrDims.add(lstrDims.get(i));
						}
					}
					mType.getSize().setDims(null);
					mType.getSize().setDynamicDims(cstrDims);
					
					// Save the location first.
	    			ASTNode parent = asgStmt.getParent();
	    			int loc = parent.getIndexOfChild(asgStmt);
	    			
					// Index Assignment of ForStmt
					ForStmt argFor = new ForStmt();
					AssignStmt idxAsg = new AssignStmt();
					idxAsg.setLHS(new NameExpr(new Name("III")));
					idxAsg.setRHS(new RangeExpr(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")), 
							new Opt<Expr>(), new NameExpr(new Name(rstrDims.get(0)))));
					argFor.setAssignStmt(idxAsg);
					
					ForStmt argFor2 = new ForStmt();
					AssignStmt idxAsg2 = new AssignStmt();
					idxAsg2.setLHS(new NameExpr(new Name("JJJ")));
					idxAsg2.setRHS(new RangeExpr(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")), 
							new Opt<Expr>(), new NameExpr(new Name(rstrDims.get(1)))));
					argFor2.setAssignStmt(idxAsg2);

					// Statement list of ForStmt
					natlab.ast.List<Stmt> forList = new natlab.ast.List<Stmt>();
					natlab.ast.List<Stmt> forList2 = new natlab.ast.List<Stmt>();
					// First assignment 
	            	natlab.ast.List<Expr> kklist1 = new natlab.ast.List<Expr>();
	            	// for(String dim: cstrDims) 
	            	kklist1.add(new NameExpr(new Name("III")));
	            	kklist1.add(new NameExpr(new Name("JJJ")));
            		kklist1.add(new ColonExpr());
	            	ParameterizedExpr kkParam1 = new ParameterizedExpr(lhs, kklist1);
	            	
	            	// assume rstrDims.size()==2 
	            	natlab.ast.List<Expr> kklist2 = new natlab.ast.List<Expr>();
	            	kklist2.add(new NameExpr(new Name("III")));
	            	kklist2.add(new NameExpr(new Name("JJJ")));
	            	ParameterizedExpr kkParam2 = new ParameterizedExpr(((NameExpr)expr.getArg(0)), kklist2);
	            	
	            	natlab.ast.List<Expr> kklist3 = new natlab.ast.List<Expr>();
	            	kklist3.add(kkParam2);
	            	int i=0;
	            	for(String dim: lstrDims) {
	            		if(i!=index)
	            			kklist3.add(new ColonExpr());
	            		i++;
	            	}
	            	expr.setArgList(kklist3);

	            	asgStmt.setLHS(kkParam1);
	            	asgStmt.setRHS(expr);
					
					// form the For statement
					forList2.add(asgStmt);
					argFor2.setStmtList(forList2);
					// form the For statement
					forList.add(argFor2);
					argFor.setStmtList(forList);
					// Add current statement
	    	    	parent.setChild(argFor, loc);
					
	    	    	// System.err.println("[III=JJJ]"+argFor.getStructureString());
	    	    	return true;
	            	
				}
				
			}			

		}
		return false;
	}
	
	// When using linear indexing, which means index misses some dimension
	// i.g.  A=Matrix(1*5),   expr:  A(2)=0.0  => A(1,2) 
	// i.g.  B=Matrix(5*1),   expr:  B(3)=0.0  => B(3,1)
	// i.g.  C=Matrix(2*10),  expr:  C(4)=0.0  => C(1,4)	// row major ??
	// 						  expr:  C(15)=0.0 => C(2,5)	// row major
	// Handle : expression:
	// i.g. A=U(:), A=U(:,:)  ==> A=U
	// Linear indexing A(i, j), A(k)=A((k-1)/j+1,(k-1)%j+1)
	// 	dd=zeros(n,1); dd(k)=5,	=>dd(k,1)=5   
	//	ff=zeros(1,n); ff(k)=5, =>ff(1,k)=5
	//	gg=zeros(1,n); gg(1)=5, =>gg(1,1)=5
	// 
	// Return value:
	//  - False: means from expr cannot get a proper type! Caller should stop using that.
	//	- True : everything is correctly 
	public static boolean adjustParameterizedExpr(MatrixType mType, ParameterizedExpr expr) {
		
		
		// Handle Array-indexing case
		if(transformArrayIndexingParamExpr(mType, expr)) {
			// False: means don't need furthur adjustment, 
			// because the mType and expr have been changed!
			return false;
		}
		
		// LSH expression type 
    	MatrixType lType = createMatrixType(mType.getName(), expr, false, false);

    	java.util.List<Integer> lDims = lType.getSize().getDims();
    	java.util.List<Integer> mDims = mType.getSize().getDims();
    	java.util.List<String> lstrDims = lType.getSize().getDynamicDims();
    	java.util.List<String> mstrDims = mType.getSize().getDynamicDims();

    	natlab.ast.List<Expr> list = new natlab.ast.List<Expr>();

    	// [1] Hand : expression:
    	// 	i.g. A=U(:), A=U(:,:)  ==> A=U
    	// 	i.g. A(:)=U, A(:,:)=U  ==> A=U, 
    	// actually it equals to A(:)=U(:), if both has one-dimension
		// The case mean(r(:)), will not be simplified, so will not fail into current function
    	if(lDims==null) {
        	boolean bWholeMatrix = true;
        	boolean bContainColon = false;
        	if(lstrDims!=null) {
        		for(String extent : lstrDims) {
        			if(extent.contains(":")) {
        				bContainColon = true;
        			}
        			if(!extent.equals(":")) {
        				bWholeMatrix = false;
        			}
        		}
        		if(bWholeMatrix) {
        			// ==> A=U
	    			ASTNode parent = expr.getParent();
	    			int loc = parent.getIndexOfChild(expr);
	    	    	parent.setChild(expr.getTarget(), loc);
	    	    	return false;
        		} else if(bContainColon) {
        			// The extern is an range expression
        			// if it's linear indexing, then need transform it into an For-loop
        			int mDimNum = 0;
        			if(mDims!=null) {
        				mDimNum = mDims.size();
        			} else {
        				mDimNum = mstrDims.size();
        			}
        			if(lstrDims.size()<mDimNum) {
        				// Transform into an For-loop
        				expr = transfromRangeIndex2Forloop(expr);
        		    	lType = createMatrixType(mType.getName(), expr, false, false);

        		    	lstrDims = lType.getSize().getDynamicDims();
        			}
        		}
        	}
    	}

    	// [2] Adjust the dimensions to be comparable 
    	// flag to indicate that lType is dynamic but mType is static,
    	// so use dynamic dimension of lType when doing the adjusting
    	boolean bExprIsDynamic = false;	  
    	
    	// Case 1: if the variable type mType is dynamic,  
    	// 			then adjust expr.type to dynamic, make them comparable
    	//	gg=zeros(1,n); gg(1)=5, =>gg(1,1)=5
    	if(lDims!=null && mDims==null) {
    		lstrDims = new ArrayList<String>(); 
        	for(Integer ext: lDims) {
        		lstrDims.add(ext.toString());
        	}
        	lDims = null;
    	} else if((lDims==null)  && (mDims!=null)) {
        	// Case 2: If expression has dynamic dimensions, lType=(n), mType=Matrix(4,4),  
        	// 		   Then change lType to same dimension, the adjust them
        	lDims = new ArrayList<Integer>();
        	for(int i=0; i<lstrDims.size(); i++) {
        		lDims.add(new Integer(0));
        	}
        	bExprIsDynamic = true;	
    	} 
    	
    	// [3] Compare two dynamic matrix type
    	// If both are dynamic, we assume one of them should be '1',
    	// and assume the difference is 1
    	// 	dd=zeros(n,1); dd(k)=5,	=>dd(k,1)=5   
    	//	ff=zeros(1,n); ff(k)=5, =>ff(1,k)=5
    	//	gg=zeros(1,n); gg(1)=5, =>gg(1,1)=5
    	if(lDims==null && mDims==null) {
    		// Adjustment only need when expr.type has less dimensions 
        	if(mstrDims.size()>lstrDims.size()) {
        		// Only handle one dimension difference between them  
            	if(mstrDims.size()==1+lstrDims.size()) {
            		// Looking for the dimension that is '1'
	        		int index = -1;
	        		for(int i=0; i<mstrDims.size(); i++) {
	        			if(mstrDims.get(i).equals("1")) {
	        				index=i; break;
	        			}
	        		}
	        		// Only handle this case, there is one dimension is '1' 
	        		if(index>=0) {
	        			int i=0;
	        			for(Expr arg: expr.getArgList()) {
		        			if(index == i) {
		        				list.add(new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
		        			}
	        				list.add(arg);
	        				// list.add(new NameExpr(new Name(lstrDims.get(i)))); // this is the value
	        				++i;
	        			}
	        			if(index == i) {
	        				list.add(new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
	        			}
	        		} else {
	            		// Cannot find dimension=1, then add ":" to it
	        			for(Expr arg: expr.getArgList()) {
	        				list.add(arg);
	        			}
        				list.add(new ColonExpr());
	        		}
	    			// Change the LHS node
	    			ASTNode parent = expr.getParent();
	    			int loc = parent.getIndexOfChild(expr);
	    	    	ParameterizedExpr funcExpr = new ParameterizedExpr(expr.getTarget(), list);
	    	    	parent.setChild(funcExpr, loc);
        		} else {
            		// Cannot handle >1 dimension difference between them  
        			System.err.println("[adjustParameterizedExpr]: cannot handle difference between ["+expr.getNodeID()+"]"+mstrDims.toString()+"<>"+lstrDims.toString());
        		}
        	}
    		return true;
    	} 
    	
    	// [4] Integer value dimensions are handled by following code
    	if(lDims.size()<mDims.size()) {
    		// Major cases are 2-dimension
    		if(mDims.size()==2) { 	// implies lDims.size()==1
    			if(mDims.get(0)==1) {
    				list.add(new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
    				list.add(expr.getArg(0));
    			} else if(mDims.get(1)==1) {
    				list.add(expr.getArg(0));
    				list.add(new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
    			} else {
    				// Linear indexing: C=Matrix(2*10), expr:  C(4)=0.0  => C(1,4) 
    				// 				  					expr:  C(15)=0.0 => C(2,5)	// row major
    				if(!bExprIsDynamic) {
	    				int row = (lDims.get(0)-1)/mDims.get(1)+1;
	    				int col = ((lDims.get(0)-1)%mDims.get(1))+1;
	    				list.add(new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue(""+row)));
	    				list.add(new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue(""+col)));

    				} else {
    		        	// Linear indexing A(i, j), A(k)=A((k-1)/j+1,(k-1)%j+1)
    					MinusExpr rowMinus = new MinusExpr(new NameExpr(new Name(lstrDims.get(0))), 
    							new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
    					MDivExpr rowDiv = new MDivExpr(rowMinus,  
    							new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue(""+mDims.get(1))));
    					PlusExpr rowIndex = new PlusExpr(rowDiv, 
    							new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));

    					MinusExpr colMinus = new MinusExpr(new NameExpr(new Name(lstrDims.get(0))), 
    							new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
    			    	natlab.ast.List<Expr> colList = new natlab.ast.List<Expr>();
						colList.add(colMinus);
						colList.add(new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue(""+mDims.get(1))));
    			    	ParameterizedExpr colParam = new ParameterizedExpr(new NameExpr(new Name("mod")), colList);
    					PlusExpr colIndex = new PlusExpr(colParam, 
    							new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
    					list.add(rowIndex);
    					list.add(colIndex);
    				}
    			}
    			// Change the LHS node
    			ASTNode parent = expr.getParent();
    			int loc = parent.getIndexOfChild(expr);
    	    	ParameterizedExpr funcExpr = new ParameterizedExpr(expr.getTarget(), list);
    	    	parent.setChild(funcExpr, loc);
    		} else {
    			System.err.println("Error: doesn't support linear indexing on three or more dimension array!");
    		}
    	} else if(lDims.size()==mDims.size()) {
    		// There is no need to adjust
    	} else { // if(lDims.size()>mDims.size())
    		// According the logic before calling this function, do not need adjust.
    		System.err.println("[Error] adjustParameterizedExpr: lDims.size()>mDims.size()");
    	}
    	// Ignor the equal case
    	return true;
	}

	
	// Transform one index, which is a Range-Expression into a For-loop
	public static ParameterizedExpr transfromRangeIndex2Forloop(ParameterizedExpr expr) {
		// if it's LHS, 
		if(expr.getParent() instanceof AssignStmt 
				&& ((AssignStmt)expr.getParent()).getLHS()==expr) {

			AssignStmt asgStmt = (AssignStmt)expr.getParent();
			// Save the location first.
			ASTNode parent = asgStmt.getParent();
			int loc = parent.getIndexOfChild(asgStmt);
			
			RangeExpr loopIdx = null;
			int index = -1;
			for(int i=0;i<expr.getNumArg(); i++) {
				if (expr.getArg(i) instanceof RangeExpr) {
					loopIdx = (RangeExpr) expr.getArg(i);
					index = i;
					break;
				}
			}
			if(index<0 || loopIdx==null) {
				return expr;
			}
			// Index Assignment of ForStmt
			ForStmt argFor = new ForStmt();
			AssignStmt idxAsg = new AssignStmt();
			idxAsg.setLHS(new NameExpr(new Name("int_tmpvar")));
			idxAsg.setRHS(loopIdx);
			argFor.setAssignStmt(idxAsg);
	
			// Statement list of ForStmt
			natlab.ast.List<Stmt> forList = new natlab.ast.List<Stmt>();
			// First assignment
			expr.setArg(new NameExpr(new Name("int_tmpvar")), index);
			forList.add((AssignStmt)expr.getParent());

			// form the For statement
			argFor.setStmtList(forList);

			// Add current statement
			parent.setChild(argFor, loc);
		}	
		return expr;
	}
	// Adjust a LHS array, represented as a name-expression,  
	// When LHS's dimension doesn't match the RHS's, needs adjust the LHS's indexing 
	// It's usually the row/column vector case. 
	// 	i.g. A=Matrix(1*5), then A=1:5  should be A(1,:)=1:5
	// 	i.g. A=Matrix(1,n), then A=1:5  should be A(1,:)=1:5
	// Parameters: 
	// 		rType: inferred type for RHS, type that want to adjust to 
	// 		lType: LHS variable type, same as it in symbol table
	// 		expr: it's a NameExpr, NOT a ParameterizedExpr!
	// Currently handle the row/column vector case. 
	// Strategy: find the dimension has '1', 
	// TODO: compare another [n] vs [n-1+1], should be >=
	// if ==; then use [n]
	// if >,  then use 1: n-1+1
	//
	public static void adjustArrayIndex(MatrixType rType, MatrixType lType, NameExpr expr) {
		// LSH expression type 
    	java.util.List<Integer> lDims = lType.getSize().getDims();
    	java.util.List<Integer> rDims = rType.getSize().getDims();	
    	natlab.ast.List<Expr> list = new natlab.ast.List<Expr>();

    	MatrixType mType = rType;
    	java.util.List<String> lstrDims = lType.getSize().getDynamicDims();
    	java.util.List<String> mstrDims = mType.getSize().getDynamicDims();
    	// Case 1: if one of the variable type mType is dynamic,  
    	// 			then adjust other to dynamic, make them comparable
    	// #1. If it's dynamic dimensions,stop 
    	if(lDims==null || rDims==null) {
        	if(lDims!=null) {
        		lstrDims = new ArrayList<String>(); 
            	for(Integer ext: lDims) {
            		lstrDims.add(ext.toString());
            	}
            	lDims = null;
        	}
        	if(rDims!=null) {
        		mstrDims = new ArrayList<String>(); 
            	for(Integer ext: rDims) {
            		mstrDims.add(ext.toString());
            	}
            	rDims = null;
        	}
        	if(lstrDims==null || mstrDims==null) 
        		return;

        	// Only need adjust when LHS has more dimensions
        	if(lstrDims.size()>mstrDims.size()) {
        		// Only handle one dimension difference between them  
            	if(lstrDims.size()==1+mstrDims.size()) {
            		// Looking for the dimension that is '1'
	        		int index = -1;
	        		for(int i=0; i<lstrDims.size(); i++) {
	        			if(lstrDims.get(i).equals("1")) {
	        				index=i; break;
	        			}
	        		}
	        		// Only handle this case, there is one dimension is '1' 
	        		if(index>=0) {
	        			int i=0;
	        			for(String extent: lstrDims) {
		        			if(index == i) {
		        				list.add(new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
		        			} else {
		        				list.add(new ColonExpr());
		        			}
	        				++i;
	        			}
		    			// Change the LHS node
		    			ASTNode parent = expr.getParent();
		    			int loc = parent.getIndexOfChild(expr);
		    	    	ParameterizedExpr funcExpr = new ParameterizedExpr(expr, list);
		    	    	parent.setChild(funcExpr, loc);
		    	    	
	        		} else {
	            		// Cannot find dimension=1
	        			System.err.println("[adjustArrayIndex]: cannot find dimension=1 in ["+expr.getNodeID()+"]"+mstrDims.size()+"[]"+lstrDims.size());
	        		}
        		} else {
            		// Cannot handle >1 dimension difference between them  
        			System.err.println("[adjustArrayIndex]: cannot handle difference between ["+expr.getNodeID()+"]"+mstrDims.size()+"[]"+lstrDims.size());
        		}
        	}
        	
    		return;
    	}

    	// #2. Handle integer value dimensions

    	// No need to handle expression A=1, where assigning 1 to whole array A; 
    	if(rDims==null || rDims.size()==0)	
    		return;
    	
    	if(lDims.size()>rDims.size()) {
    		// Major cases are 2-dimension, i.g. A=1:5; A=[1,2,3,4,5]
    		// Since LHS is just a NameExpr, not a parameterized-expression, 
    		// If LHS/RHS dimension difference is 1, then add ':' colon-expression to LHS
    		if(lDims.size()==2 && rDims.size()==1) { 
    			if(lDims.get(0)==1) {
    				list.add(new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
    				list.add(new ColonExpr());
    			} else if(lDims.get(1)==1) {
    				list.add(new ColonExpr());
    				list.add(new natlab.ast.IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
    			} else {
    				// In case: C=Matrix(2*10),  expr:  C=[1,2] ??? Grammar Error
    	    		System.err.println("[Error]adjustArrayIndex: lhs=["+lDims.get(0)+","+lDims.get(1)+"]");
    			}
    			// Change the LHS node
    			ASTNode parent = expr.getParent();
    			int loc = parent.getIndexOfChild(expr);
    	    	ParameterizedExpr funcExpr = new ParameterizedExpr(expr, list);
    	    	parent.setChild(funcExpr, loc);
    		} else {
        		System.err.println("[Other Cases]adjustArrayIndex: lhs=["+lDims.get(0)+","+lDims.get(1)+"]"
        				+ " rhs=["+rDims.get(0)+","+rDims.get(1)+"]"); 
    		}
    	} else if(lDims.size()<rDims.size()) {
    		// According the logic before calling this function, this should not happen!
    		System.err.println("[Error]adjustArrayIndex: lDims.size()<rDims.size()");
    	} 
    	// Ignor the equal case     	
	}
	
	
	public static void adjustArrayIndex(PrimitiveType rType, MatrixType lType, BinaryExpr expr) {
		Expr lhs = expr.getLHS();
		if(lhs instanceof NameExpr) {
			adjustArrayIndex(rType, lType, (NameExpr)lhs);
		} 
		Expr rhs = expr.getLHS();
		if(rhs instanceof NameExpr) {
			adjustArrayIndex(rType, lType, (NameExpr)rhs);
		} 
	}
	// Change array to A(1,1)
	public static void adjustArrayIndex(PrimitiveType rType, MatrixType lType, NameExpr expr) {
    	natlab.ast.List<Expr> list = new natlab.ast.List<Expr>();
    	java.util.List<Integer> lDims = lType.getSize().getDims();
    	java.util.List<String> lstrDims = lType.getSize().getDynamicDims();
    	if(lDims!=null) {
			for(Integer dim:lDims)
				list.add(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
    	} else if(lstrDims!=null) {
			for(String str:lstrDims)
				list.add(new IntLiteralExpr(new natlab.DecIntNumericLiteralValue("1")));
    	}
		// Change the LHS node
		ASTNode parent = expr.getParent();
		int loc = parent.getIndexOfChild(expr);
    	ParameterizedExpr funcExpr = new ParameterizedExpr(expr, list);
    	parent.setChild(funcExpr, loc);
	}
	
	
	// Create a function signature from a function call
	// Assumption: all parameters are either variables (have been added into symbol table)
	//				or constants
	// TODO: Testing createFunctionSignature()
	public static Type createFunctionSignature(SymbolTableScope stScope, 
			ParameterizedExpr expr, ASTNode varNode)
	{	
		ArgTupleType funcSignature = new ArgTupleType(); 
		funcSignature.setName(expr.getVarName());
        List<Expr> args = expr.getArgs();
        for(Expr arg : args) {
        	Type argType; String argName;
        	if(arg instanceof LiteralExpr) {
	        	if(arg instanceof IntLiteralExpr) {
	        		argType = new PrimitiveType(TYPENAME_INTEGER);
	        		argName = ((IntLiteralExpr) arg).getValue().getText();
	        	} else if(arg instanceof FPLiteralExpr) {
	        		argType = new PrimitiveType(TYPENAME_DOUBLE);
	        		argName = ((FPLiteralExpr) arg).getValue().getText();
	        	} else { // StringLiteralExpr
	        		argType = new PrimitiveType("character(50)");
	        		argName = ((StringLiteralExpr) arg).getValue();
	        	}
        	} else {
            	argType = arg.collectType(stScope, arg);
            	argName = arg.getVarName();
        	}
        	funcSignature.addStaticArgType(argType, argName);
        }
		return funcSignature;
	}

	// 
	// Get type of expression, 
	// 
	public static Type getVariableType(SymbolTableScope stScope, Expr expr) {
		Type varType=null;
		String varName = expr.getVarName();
		if((expr instanceof LiteralExpr)) {
			varType = expr.collectType(stScope, expr);
		} else {
			SymbolTableEntry rhsEntry = stScope.getSymbolById(varName);
			if(rhsEntry!=null && rhsEntry.getDeclLocation()!=null) {
				varType = ((VariableDecl)rhsEntry.getDeclLocation()).getType();
			} else { 
				varType = expr.collectType(stScope, expr);
			}
		} 
		
		return varType;
	}
	// 
	// Get the string combining with values of each element node in the expression sub-tree	
	// BTW, the stScope may not necessary
	// 
	public static String getVariableValue(SymbolTableScope stScope, Expr expr) {
		String strValue=null;
		String varName = expr.getVarName();
		// If it an variable  
		if((expr instanceof LiteralExpr)) {
			strValue = getLiteralString((LiteralExpr)expr);
		} else if((expr instanceof NameExpr)) {
			varName = ((NameExpr) expr).getName().getID();
			SymbolTableEntry rhsEntry = stScope.getSymbolById(varName);
			if(rhsEntry!=null) {
				strValue = getLiteralString(rhsEntry.getValue());
			} else { 
    			// Handle the un-initialized variable, such as 'i' the imagine sign
				System.err.println("[getVarValue]<"+expr+"> "+varName+" doesn't have entry");				
				strValue = ((NameExpr) expr).getName().getID();
				throw new TypeInferException(varName);
			}
		} else if ((expr instanceof ParameterizedExpr)) {
			// Array variable access can be checked by 			        			
			// 		SymbolTableEntry rhsEntry = stScope.getSymbolById(rhsName);
			// Otherwise it's a function call
			// But since here don't support array variable, so treat them same
			// strValue = expr.getFortran();
	        StringBuffer buf = new StringBuffer();
	        List<Expr> args = ((ParameterizedExpr) expr).getArgs();
        	buf.append(((ParameterizedExpr) expr).getVarName());
            buf.append("(");
            boolean first = true;
            for(Expr arg : args) {
                if(!first) {
                    buf.append(", ");
                }
                buf.append(getVariableValue(stScope, arg));
                first = false;
            }
            buf.append(")");
			strValue = buf.toString();
			
		} else if (expr instanceof BinaryExpr){
			String opStr = null;
			if(expr instanceof PlusExpr) {
				opStr = "+";
			} else if(expr instanceof MinusExpr) {
				opStr = "-";
			} else if(expr instanceof MTimesExpr) {
				opStr = "*";
			} else if(expr instanceof MDivExpr) {
				opStr = "/";
			} else {
				
			}
			String lhsStr = getVariableValue(stScope, ((BinaryExpr) expr).getLHS());
			String rhsStr = getVariableValue(stScope, ((BinaryExpr) expr).getRHS());
			if(opStr!=null && lhsStr!=null && rhsStr!=null) {
				strValue ="("+lhsStr+opStr+rhsStr+")";
			} else {
				strValue = null;
			}

		} else if (expr instanceof UnaryExpr){
			String opStr = "+";
			if(expr instanceof UPlusExpr) {
				opStr = "+";
			} else if(expr instanceof UMinusExpr) {
				opStr = "-";
			}
			strValue = "("+opStr+getVariableValue(stScope, ((UnaryExpr) expr).getOperand())+")";
			
// TODO
		} else if ((expr instanceof RangeExpr)) {
			// Need to calculate the value of Range Expr!
			// Detailed ones is in inferSymbolEntry(), because it needs 2 values
			String strLow = getVariableValue(stScope, ((RangeExpr)expr).getLower());
			String strUpp = getVariableValue(stScope, ((RangeExpr)expr).getUpper());
			strValue = strLow + ":" + strUpp;
		} else {	
			// unsure situation
			strValue = null;	// expr.getFortran();
		}
		return strValue;
	}
	
	// 
	// Get the integer value with values of each element node in the expression sub-tree
	// If all the nodes are IntLiteralExpr, then will return value of the expression. 
	// Returns:
	//	- ERROR_EXTENT: error occured
	//	- otherwise, the value of the expression
	public static int getExprIntegerValue(ExprStmt exprStmt) {
		return getExprIntegerValue(exprStmt.getExpr());
	}
	public static int getExprIntegerValue(Expr  expr) {
		int resultValue=ERROR_EXTENT;
		
		// If it an variable  
		// if((expr instanceof LiteralExpr)) 
		if((expr instanceof LiteralExpr)) {
			if(isIntLiteral(expr)) {
				resultValue = getIntLiteralValue((LiteralExpr)expr);
			} else if(isFPLiteral(expr)) {
				resultValue = (int) getFPLiteralValue((LiteralExpr)expr);
			} else {
				return ERROR_EXTENT;
			}
		} else if (expr instanceof BinaryExpr){
			int lhsValue = getExprIntegerValue(((BinaryExpr) expr).getLHS());
			int rhsValue = getExprIntegerValue(((BinaryExpr) expr).getRHS());
			if(expr instanceof PlusExpr) {
				resultValue = lhsValue + rhsValue;
			} else if(expr instanceof MinusExpr) {
				resultValue = lhsValue - rhsValue;
			} else if(expr instanceof MTimesExpr) {
				resultValue = lhsValue * rhsValue;
			} else if(expr instanceof MDivExpr) {
				resultValue = lhsValue / rhsValue;
			} else {
				// Unsupport operations
				return ERROR_EXTENT;
			}
		} else if (expr instanceof UnaryExpr){
			resultValue = getExprIntegerValue(((UnaryExpr) expr).getOperand());
			// if(expr instanceof UPlusExpr) 
			if(expr instanceof UMinusExpr) {
				resultValue = -resultValue;
			}
		} else {	
			// if((expr instanceof NameExpr)) {
			// if ((expr instanceof ParameterizedExpr)) {
			// if ((expr instanceof RangeExpr)) {
			// Unsupport AST type
			return ERROR_EXTENT;
		}
		return resultValue;
	}
}
