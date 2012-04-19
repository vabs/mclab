// =========================================================================== //
//                                                                             //
// Copyright 2011 Anton Dubrau and McGill University.                          //
//                                                                             //
//   Licensed under the Apache License, Version 2.0 (the "License");           //
//   you may not use this file except in compliance with the License.          //
//   You may obtain a copy of the License at                                   //
//                                                                             //
//       http://www.apache.org/licenses/LICENSE-2.0                            //
//                                                                             //
//   Unless required by applicable law or agreed to in writing, software       //
//   distributed under the License is distributed on an "AS IS" BASIS,         //
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  //
//   See the License for the specific language governing permissions and       //
//  limitations under the License.                                             //
//                                                                             //
// =========================================================================== //

package natlab.tame.tir;
import ast.Name;
import ast.NameExpr;


public abstract class TIRAbstractAssignToVarStmt extends TIRAbstractAssignStmt {
    private static final long serialVersionUID = 1L;

    public TIRAbstractAssignToVarStmt(Name lhs) {
        super();
        setLHS(new NameExpr(lhs));
    }
    
    /**
     * returns the name of the variable which is on the LHS as a Name
     * @return
     */
    public Name getTargetName(){
        return ((NameExpr)getLHS()).getName();
    }
}