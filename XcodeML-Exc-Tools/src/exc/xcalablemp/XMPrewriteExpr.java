/*
 * $TSUKUBA_Release: $
 * $TSUKUBA_Copyright:
 *  $
 */

package exc.xcalablemp;

import exc.block.*;
import exc.object.*;
import exc.openacc.ACCpragma;

import java.util.*;

public class XMPrewriteExpr {
  private XMPglobalDecl		_globalDecl;

  public XMPrewriteExpr(XMPglobalDecl globalDecl) {
    _globalDecl = globalDecl;
  }

  public void rewrite(FuncDefBlock def) {
    FunctionBlock fb = def.getBlock();
    if (fb == null) return;

    // get symbol table
    XMPsymbolTable localXMPsymbolTable = XMPlocalDecl.declXMPsymbolTable(fb);

    // rewrite parameters
    rewriteParams(fb, localXMPsymbolTable);

    // rewrite declarations
    rewriteDecls(fb, localXMPsymbolTable);

    // rewrite Function Exprs
    rewriteFuncExprs(fb, localXMPsymbolTable);

    // rewrite OMP pragma
    rewriteOMPpragma(fb, localXMPsymbolTable);
    
    // rewrite ACC pragma
    rewriteACCpragma(fb, localXMPsymbolTable);

    // create local object descriptors, constructors and desctructors
    XMPlocalDecl.setupObjectId(fb);
    XMPlocalDecl.setupConstructor(fb);
    XMPlocalDecl.setupDestructor(fb);

    def.Finalize();
  }

  private void rewriteParams(FunctionBlock funcBlock, XMPsymbolTable localXMPsymbolTable) {
    XobjList identList = funcBlock.getBody().getIdentList();
    if (identList == null) {
      return;
    } else {
      for(Xobject x : identList) {
        Ident id = (Ident)x;
        XMPalignedArray alignedArray = localXMPsymbolTable.getXMPalignedArray(id.getName());
        if (alignedArray != null) {
          id.setType(Xtype.Pointer(alignedArray.getType()));
        }
      }
    }
  }

  private void rewriteDecls(FunctionBlock funcBlock, XMPsymbolTable localXMPsymbolTable) {
    topdownBlockIterator iter = new topdownBlockIterator(funcBlock);
    for (iter.init(); !iter.end(); iter.next()) {
      Block b = iter.getBlock();
      BlockList bl = b.getBody();

      if (bl != null) {
        XobjList decls = (XobjList)bl.getDecls();
        if (decls != null) {
          try {
            for (Xobject x : decls) {
              Xobject declInitExpr = x.getArg(1);
	      x.setArg(1, rewriteExpr(declInitExpr, b));
            }
          } catch (XMPexception e) {
            XMP.error(b.getLineNo(), e.getMessage());
          }
        }
      }
    }
  }

  private void rewriteFuncExprs(FunctionBlock funcBlock, XMPsymbolTable localXMPsymbolTable) {
    // insert TASK descripter for cache mechanism.
    if(_globalDecl.findVarIdent(funcBlock.getName()).Type().isInline() == false){

      // This decleartion is inserted into the first point of each function.
      BlockList taskBody = funcBlock.getBody().getHead().getBody();
      Ident taskDescId = taskBody.declLocalIdent("_XMP_TASK_desc", Xtype.voidPtrType, StorageClass.AUTO,
						 Xcons.Cast(Xtype.voidPtrType, Xcons.IntConstant(0)));
      
      // insert Finalize function into the last point of each function.
      XobjList arg = Xcons.List(Xcode.POINTER_REF, taskDescId.Ref());
      Ident taskFuncId = _globalDecl.declExternFunc("_XMP_exec_task_NODES_FINALIZE");
      taskBody.add(taskFuncId.Call(arg));

      // insert Finalize function into the previous point of return statement
      BlockIterator i = new topdownBlockIterator(taskBody);
      for (i.init(); !i.end(); i.next()) {
	Block b = i.getBlock();
	if (b.Opcode() == Xcode.RETURN_STATEMENT){
	  b.insert(taskFuncId.Call(arg));
	}
      }
    }

    BasicBlockExprIterator iter = new BasicBlockExprIterator(funcBlock);
    for (iter.init(); !iter.end(); iter.next()) {
      Xobject expr = iter.getExpr();
      try {
        switch (expr.Opcode()) {
          case ASSIGN_EXPR:
	    iter.setExpr(rewriteAssignExpr(expr, iter.getBasicBlock().getParent(), localXMPsymbolTable, iter));
            break;
          default:
	    iter.setExpr(rewriteExpr(expr, iter.getBasicBlock().getParent()));
            break;
        }
      } catch (XMPexception e) {
        XMP.error(expr.getLineNo(), e.getMessage());
      }
    }
  }

  private Xobject rewriteAssignExpr(Xobject myExpr, Block exprParentBlock, XMPsymbolTable localXMPsymbolTable,
                                    BasicBlockExprIterator iter) throws XMPexception {
    assert myExpr.Opcode() == Xcode.ASSIGN_EXPR;

    Xobject leftExpr = myExpr.getArg(0);
    Xobject rightExpr = myExpr.getArg(1);

    if ((leftExpr.Opcode() == Xcode.CO_ARRAY_REF) && (rightExpr.Opcode() == Xcode.CO_ARRAY_REF)) {   // a:[1] = b[2];   // Fix me
      throw new XMPexception("unknown co-array expression"); 
    } 
    else if ((leftExpr.Opcode() == Xcode.CO_ARRAY_REF) || (rightExpr.Opcode() == Xcode.CO_ARRAY_REF)) {
      return rewriteCoarrayAssignExpr(myExpr, exprParentBlock, localXMPsymbolTable, iter);
    } 
    else {
      return rewriteExpr(myExpr, exprParentBlock);
    }
  }

  //void _XMP_shortcut_put_image1(int target_image, _XMP_coarray_t *dst_desc,
  //      _XMP_coarray_t *src_desc, int dst_point, int src_point, int transfer_length)
  //void _XMP_shortcut_put_image2(int target_image1, int target_image2, _XMP_coarray_t *dst_desc, ...)
  private Xobject createShortcutCoarray(int imageDims, XobjList imageList, String commkind, 
                                        XMPcoarray dstCoarray, XMPcoarray srcCoarray,
                                        Xobject dstCoarrayExpr, Xobject srcCoarrayExpr) throws XMPexception
  // dstCoarray is left expression. srcCoarray is right expression.
  {
    // Set Function Name
    // If image set is 2 dimension and Put operation,
    // function name is "_XMP_shortcut_put_image2"
    String funcName = "_XMP_coarray_shortcut_" + commkind;
    Ident funcId = _globalDecl.declExternFunc(funcName);
    XobjList funcArgs = Xcons.List();

    // Set target image
    XMPcoarray remoteCoarray;
    if(commkind == "put"){
      remoteCoarray = dstCoarray;
    } else{
      remoteCoarray = srcCoarray;
    }
    
    // Distance Image to need increment in each dimension
    // e.g.) a:[4][3][2][*];
    //       remoteImageDistance[0] = 4 * 3 * 2;
    //       remoteImageDistance[1] = 4 * 3;
    //       remoteImageDistance[2] = 4;
    //       remoteImageDistance[3] = 1; // Note: the last dimension must be 1.
    int[] remoteImageDistance = new int[imageDims];
    for(int i=0;i<imageDims-1;i++){
      remoteImageDistance[i] = 1;
      for(int j=0;j<imageDims-1-i;j++){
        remoteImageDistance[i] *= remoteCoarray.getImageAt(j);
      }
    }
    remoteImageDistance[imageDims-1] = 1;

    //    Xobject targetImage = Xcons.binaryOp(Xcode.MINUS_EXPR, imageList.getArg(0), Xcons.IntConstant(1));
    Xobject targetImage = imageList.getArg(0);
    for(int i=1;i<imageDims;i++){
      Xobject tmp = Xcons.binaryOp(Xcode.MUL_EXPR, 
                                   Xcons.binaryOp(Xcode.MINUS_EXPR, imageList.getArg(i), Xcons.IntConstant(1)),
                                   Xcons.IntConstant(remoteImageDistance[imageDims-1-i]));
      targetImage = Xcons.binaryOp(Xcode.PLUS_EXPR, tmp, targetImage);
    }
    funcArgs.add(targetImage);

    // Set Coarray Descriptor
    funcArgs.add(Xcons.SymbolRef(dstCoarray.getDescId()));
    funcArgs.add(Xcons.SymbolRef(srcCoarray.getDescId()));
    
    // Number of elements to need increment in each dimension
    // e.g.) a[3][4][5][6];
    //       xxxCoarrayDistance[0] = 4 * 5 * 6;
    //       xxxCoarrayDistance[1] = 5 * 6;
    //       xxxCoarrayDistance[2] = 6;
    //       xxxCoarrayDistance[3] = 1; // Note: the last dimension must be 1.
    int dstDim = dstCoarray.getVarDim();
    int srcDim = srcCoarray.getVarDim();
    int[] dstCoarrayDistance = new int[dstDim];
    int[] srcCoarrayDistance = new int[srcDim];

    if(dstCoarrayExpr.Opcode() == Xcode.VAR){
      dstCoarrayDistance[0] = 1;
    }
    else{
      for(int i=0;i<dstDim;i++){
        dstCoarrayDistance[i] = 1;
        for(int j=i+1;j<dstDim;j++){
          dstCoarrayDistance[i] *= (int)dstCoarray.getSizeAt(j);
        }
      }
    }

    if(srcCoarrayExpr.Opcode() == Xcode.VAR){
      srcCoarrayDistance[0] = 1;
    }
    else{
      for(int i=0;i<srcDim;i++){
        srcCoarrayDistance[i] = 1;
        for(int j=i+1;j<srcDim;j++){
          srcCoarrayDistance[i] *= (int)srcCoarray.getSizeAt(j);
        }
      }
    }

    // How depth continuous ?
    // e.g.) a[100][100][100]:[*];
    //       a[:][:][:],   xxxCoarrayDepthContinuous = 0
    //       a[2][:][:],   xxxCoarrayDepthContinuous = 1
    //       a[:2][:][:],  xxxCoarrayDepthContinuous = 1
    //       a[2][2][:],   xxxCoarrayDepthContinuous = 2
    //       a[:][:2][:],  xxxCoarrayDepthContinuous = 2
    //       a[2][2][2:2], xxxCoarrayDepthContinuous = 3
    //       a[2][2][2:],  xxxCoarrayDepthContinuous = 3
    //       a[2][2][:],   xxxCoarrayDepthContinuous = 3
    //       a[2][2][1],   xxxCoarrayDepthContinuous = 3

    // dstCoarray
    int dstCoarrayDepthContinuous = dstDim;
    if(dstCoarrayExpr.Opcode() == Xcode.SUB_ARRAY_REF){
      Ident varId = dstCoarray.getVarId();
      XobjList tripletList = (XobjList)dstCoarrayExpr.getArg(1);
      for(int i=dstDim-1;i>=0;i--){
        if(is_all_element(i, tripletList, varId)){
          dstCoarrayDepthContinuous = i;
        }
      }
    }
    // if dstCoarray == Xcode.ARRAY_REF or Xcode.VAR,
    // dstCoarrayDepthContinuous = 1.
    
    int srcCoarrayDepthContinuous = srcDim;
    if(srcCoarrayExpr.Opcode() == Xcode.SUB_ARRAY_REF){
      Ident varId = srcCoarray.getVarId();
      XobjList tripletList = (XobjList)srcCoarrayExpr.getArg(1);
      for(int i=srcDim-1;i>=0;i--){
        if(is_all_element(i, tripletList, varId)){
          srcCoarrayDepthContinuous = i;
        }
      }
    }

    // dst offset
    Xobject position = null;
    if(dstCoarrayExpr.Opcode() == Xcode.SUB_ARRAY_REF){
      for(int i=0;i<dstDim;i++){
        Xobject tripletList = dstCoarrayExpr.getArg(1).getArg(i);
        Xobject tmp_position;
        if(tripletList.isConstant() || tripletList.isVariable()){
          tmp_position = Xcons.binaryOp(Xcode.MUL_EXPR, tripletList, Xcons.IntConstant(dstCoarrayDistance[i]));
        }
        else{
          Xobject start = ((XobjList)tripletList).getArg(0);
          tmp_position = Xcons.binaryOp(Xcode.MUL_EXPR, start, Xcons.IntConstant(dstCoarrayDistance[i]));
        }
        if(i == 0){
          position = tmp_position;
        }
        else{
          position = Xcons.binaryOp(Xcode.PLUS_EXPR, position, tmp_position);
        }
      }
    }
    else if(dstCoarrayExpr.Opcode() == Xcode.ARRAY_REF){
      for(int i=0;i<dstDim;i++){
        Xobject tmp_position = Xcons.binaryOp(Xcode.MUL_EXPR, dstCoarrayExpr.getArg(1).getArg(i),
                                              Xcons.IntConstant(dstCoarrayDistance[i]));
        if(i==0){
          position = tmp_position;
        }
        else{
          position = Xcons.binaryOp(Xcode.PLUS_EXPR, position, tmp_position);
        }
      }
    }
    else if(dstCoarrayExpr.Opcode() == Xcode.VAR){
      position = Xcons.IntConstant(0);
    }
    else{
      throw new XMPexception("Not supported this coarray Syntax");
    }
    Xtype elmtType = dstCoarray.getElmtType();
    position = Xcons.binaryOp(Xcode.MUL_EXPR, position, Xcons.SizeOf(elmtType));
    funcArgs.add(position);

    // src offset
    position = null;
    if(srcCoarrayExpr.Opcode() == Xcode.SUB_ARRAY_REF){
      for(int i=0;i<srcDim;i++){
        Xobject tripletList = srcCoarrayExpr.getArg(1).getArg(i);
        Xobject tmp_position;
        if(tripletList.isConstant() || tripletList.isVariable()){
          tmp_position = Xcons.binaryOp(Xcode.MUL_EXPR, tripletList, Xcons.IntConstant(srcCoarrayDistance[i]));
        }
        else{
          Xobject start = ((XobjList)tripletList).getArg(0);
          tmp_position = Xcons.binaryOp(Xcode.MUL_EXPR, start, Xcons.IntConstant(srcCoarrayDistance[i]));
        }
        if(i == 0){
          position = tmp_position;
        }
        else{
          position = Xcons.binaryOp(Xcode.PLUS_EXPR, position, tmp_position);
        }
      }
    }
    else if(srcCoarrayExpr.Opcode() == Xcode.ARRAY_REF){
      for(int i=0;i<srcDim;i++){
        Xobject tmp_position = Xcons.binaryOp(Xcode.MUL_EXPR, srcCoarrayExpr.getArg(1).getArg(i),
                                              Xcons.IntConstant(srcCoarrayDistance[i]));
        if(i==0){
          position = tmp_position;
        }
        else{
          position = Xcons.binaryOp(Xcode.PLUS_EXPR, position, tmp_position);
        }
      }
    }
    else if(srcCoarrayExpr.Opcode() == Xcode.VAR){
      position = Xcons.IntConstant(0);
    }
    else{
      throw new XMPexception("Not supported this coarray Syntax");
    }
    position = Xcons.binaryOp(Xcode.MUL_EXPR, position, Xcons.SizeOf(elmtType));
    funcArgs.add(position);

    // length
    Xobject length = null;
    if(dstCoarrayExpr.Opcode() == Xcode.SUB_ARRAY_REF){
      if(dstCoarrayDepthContinuous == 0){
        length = Xcons.IntConstant((int)dstCoarray.getSizeAt(0) * dstCoarrayDistance[0]);
      }
      else{
        Xobject tripletList = dstCoarrayExpr.getArg(1).getArg(dstCoarrayDepthContinuous-1);
        if(tripletList.isConstant() || tripletList.isVariable()){
          length = Xcons.IntConstant(dstCoarrayDistance[dstCoarrayDepthContinuous-1]);
        }
        else{
          length = Xcons.binaryOp(Xcode.MUL_EXPR, ((XobjList)tripletList).getArg(1),
                                  Xcons.IntConstant(dstCoarrayDistance[dstCoarrayDepthContinuous-1]));
        }
      }
    }
    else if(dstCoarrayExpr.Opcode() == Xcode.ARRAY_REF || dstCoarrayExpr.Opcode() == Xcode.VAR){
      length = Xcons.IntConstant(1);
    }
    else{
      throw new XMPexception("Not supported this coarray Syntax");
    }
    length = Xcons.binaryOp(Xcode.MUL_EXPR, length, Xcons.SizeOf(elmtType));
    funcArgs.add(length);

    // Create function
    Xobject newExpr = funcId.Call(funcArgs);
    newExpr.setIsRewrittedByXmp(true);
    return newExpr;
  }

  private Xobject rewriteCoarrayAssignExpr(Xobject myExpr, Block exprParentBlock, XMPsymbolTable localXMPsymbolTable, 
                                           BasicBlockExprIterator iter) throws XMPexception {
    assert myExpr.Opcode() == Xcode.ASSIGN_EXPR;

    Xobject leftExpr    = myExpr.getArg(0);
    Xobject rightExpr   = myExpr.getArg(1);
    Xobject coarrayExpr = null;
    Xobject localExpr   = null;
    if(leftExpr.Opcode() == Xcode.CO_ARRAY_REF){  // PUT
      coarrayExpr = leftExpr;
      localExpr   = rightExpr;
    } else{                                       // GET
      coarrayExpr = rightExpr;
      localExpr   = leftExpr;
    }

    String coarrayName = XMPutil.getXobjSymbolName(coarrayExpr.getArg(0));
    XMPcoarray coarray = _globalDecl.getXMPcoarray(coarrayName, exprParentBlock);
    if(coarray == null){
      throw new XMPexception("cannot find coarray '" + coarrayName + "'");
    }
    
    // Get Coarray Dims
    XobjList funcArgs = Xcons.List();
    int coarrayDims;
    if(coarrayExpr.getArg(0).Opcode() == Xcode.SUB_ARRAY_REF || coarrayExpr.getArg(0).Opcode() == Xcode.ARRAY_REF){
      XobjList tripletList = (XobjList)(coarrayExpr.getArg(0)).getArg(1);
      coarrayDims = tripletList.Nargs();
    }
    else if(coarrayExpr.getArg(0).Opcode() == Xcode.VAR){
      coarrayDims = 1;
    }
    else{
      throw new XMPexception("Not supported this coarray Syntax");
    }

    // Get Local Dims
    boolean isArray;
    int localDims;
    String localName;
    if(localExpr.Opcode() == Xcode.SUB_ARRAY_REF || localExpr.Opcode() == Xcode.ARRAY_REF){
      isArray = true;
      localName = localExpr.getArg(0).getName();
      Ident varId = localExpr.findVarIdent(localName);
      localDims = varId.Type().getNumDimensions();
    }
    else if(localExpr.Opcode() == Xcode.VAR){
      localName = localExpr.getName();
      isArray = false;
      localDims = 1;
    }
    else if(localExpr.Opcode() == Xcode.ARRAY_ADDR){
      throw new XMPexception("Array pointer is used at coarray Syntax");
    }
    else if(localExpr.isConstant()){  // Fix me
      throw new XMPexception("Not supported a Constant Value at coarray Syntax");
    }
    else{
      throw new XMPexception("Not supported this coarray Syntax");
    }

    // Get image Dims
    XobjList imageList = (XobjList)coarrayExpr.getArg(1);
    int imageDims = imageList.Nargs();

    // Shortcut Function
    if(isContinuousArray(coarrayExpr, exprParentBlock) &&
       isContinuousArray(localExpr, exprParentBlock) &&
       isCoarray(localExpr, exprParentBlock))
      {
        XMPcoarray remoteCoarray = coarray;
        XMPcoarray localCoarray = _globalDecl.getXMPcoarray(localName, exprParentBlock);
        if(leftExpr.Opcode() == Xcode.CO_ARRAY_REF)
          {  // put a[:]:[1] = b[:];
            return createShortcutCoarray(imageDims, imageList, "put", remoteCoarray, localCoarray,
                                         coarrayExpr.getArg(0), localExpr);
          }
        else{ // get a[:] = b[:]:[1]
          return createShortcutCoarray(imageDims, imageList, "get", localCoarray, remoteCoarray,
                                       localExpr, coarrayExpr.getArg(0));
        }
      }

    // Set function _XMP_coarray_rdma_set()
    /*
    Ident funcId = _globalDecl.declExternFunc("_XMP_coarray_rdma_set");
    funcArgs.add(Xcons.IntConstant(coarrayDims));
    funcArgs.add(Xcons.IntConstant(localDims));
    funcArgs.add(Xcons.IntConstant(imageDims));
    Xobject newExpr = funcId.Call(funcArgs);
    newExpr.setIsRewrittedByXmp(true);
    b.add(newExpr);
    */

    // Set function _XMP_coarray_rdma_coarray_set_X()
    Ident funcId;
    funcArgs = Xcons.List();
    if(coarrayExpr.getArg(0).Opcode() == Xcode.SUB_ARRAY_REF){
      XobjList tripletList = (XobjList)(coarrayExpr.getArg(0)).getArg(1);
      funcId = _globalDecl.declExternFunc("_XMP_coarray_rdma_coarray_set_" + Integer.toString(tripletList.Nargs()));
      for(int i=0;i<tripletList.Nargs();i++){
        if(tripletList.getArg(i).isConstant() || tripletList.getArg(i).isVariable()){
          funcArgs.add(tripletList.getArg(i)); // start
          funcArgs.add(Xcons.IntConstant(1));  // length
          funcArgs.add(Xcons.IntConstant(1));  // stride
        }
        else{
          for(int j=0;j<3;j++){
            funcArgs.add(tripletList.getArg(i).getArg(j));
          }
        }
      }
    }
    else if(coarrayExpr.getArg(0).Opcode() == Xcode.ARRAY_REF){
      funcId = _globalDecl.declExternFunc("_XMP_coarray_rdma_coarray_set_1");
      XobjList startList = (XobjList)(coarrayExpr.getArg(0)).getArg(1);
      funcId = _globalDecl.declExternFunc("_XMP_coarray_rdma_coarray_set_" + Integer.toString(startList.Nargs()));
      for(int i=0;i<startList.Nargs();i++){
        funcArgs.add(startList.getArg(i));  // start
	funcArgs.add(Xcons.IntConstant(1)); // length
        funcArgs.add(Xcons.IntConstant(1)); // stride
      }
    }
    else if(coarrayExpr.getArg(0).Opcode() == Xcode.VAR){
      funcId = _globalDecl.declExternFunc("_XMP_coarray_rdma_coarray_set_1");
      funcArgs.add(Xcons.IntConstant(0)); // start
      funcArgs.add(Xcons.IntConstant(1)); // length
      funcArgs.add(Xcons.IntConstant(1)); // stride
    }
    else{
      throw new XMPexception("Not supported this coarray Syntax");
    }
    Xobject newExpr = funcId.Call(funcArgs);
    newExpr.setIsRewrittedByXmp(true);
    iter.insertStatement(newExpr);

    // Set function _XMP_coarray_rdma_array_set_X()
    funcArgs = Xcons.List();
    if(isArray){
      String arrayName = localExpr.getArg(0).getName();
      Ident varId = localExpr.findVarIdent(arrayName);
      Xtype varType = varId.Type();
      Xtype elmtType = varType.getArrayElementType();
      int varDim = varType.getNumDimensions();
      funcId = _globalDecl.declExternFunc("_XMP_coarray_rdma_array_set_" + Integer.toString(varDim));
      Integer[] sizeArray = new Integer[varDim];
      Integer[] distanceArray = new Integer[varDim];

      for(int i=0;i<varDim;i++,varType=varType.getRef()){
        int dimSize = (int)varType.getArraySize();
        if((dimSize == 0) || (dimSize == -1)){
          throw new XMPexception("array size should be declared statically");
        }
        sizeArray[i] = dimSize;
      }

      for(int i=0;i<varDim-1;i++){
	int tmp = 1;
	for(int j=i+1;j<varDim;j++){
	  tmp *= sizeArray[j];
	}
	distanceArray[i] = tmp;
      }
      distanceArray[varDim-1] = 1;

      XobjList tripletList = (XobjList)localExpr.getArg(1);
      for(int i=0;i<tripletList.Nargs();i++){
        if(tripletList.getArg(i).isVariable() || tripletList.getArg(i).isIntConstant() ){
          funcArgs.add(tripletList.getArg(i));           // start
          funcArgs.add(Xcons.IntConstant(1));            // length
          funcArgs.add(Xcons.IntConstant(1));            // stride
          funcArgs.add(Xcons.IntConstant(sizeArray[i])); // size
	  funcArgs.add(Xcons.binaryOp(Xcode.MUL_EXPR, Xcons.IntConstant(distanceArray[i]), Xcons.SizeOf(elmtType))); // distance
        }
        else{
          for(int j=0;j<3;j++){
            funcArgs.add(tripletList.getArg(i).getArg(j));
          }
          funcArgs.add(Xcons.IntConstant(sizeArray[i]));     // size
	  funcArgs.add(Xcons.binaryOp(Xcode.MUL_EXPR, Xcons.IntConstant(distanceArray[i]), Xcons.SizeOf(elmtType)));
        }
      }
    }
    else{  // !isArray
      funcId = _globalDecl.declExternFunc("_XMP_coarray_rdma_array_set_1");
      funcArgs.add(Xcons.IntConstant(0)); // start
      funcArgs.add(Xcons.IntConstant(1)); // length
      funcArgs.add(Xcons.IntConstant(1)); // stride
      funcArgs.add(Xcons.IntConstant(1)); // size
      funcArgs.add(Xcons.SizeOf(localExpr.Type()));
    }
    newExpr = funcId.Call(funcArgs);
    newExpr.setIsRewrittedByXmp(true);
    iter.insertStatement(newExpr);

    // Set function _XMP_coarray_rdma_node_set_X()
    funcId = _globalDecl.declExternFunc("_XMP_coarray_rdma_node_set_" + Integer.toString(imageDims));
    funcArgs = Xcons.List();
    for(int i=0;i<imageDims;i++){
      funcArgs.add(imageList.getArg(i));
    }
    newExpr = funcId.Call(funcArgs);
    newExpr.setIsRewrittedByXmp(true);
    iter.insertStatement(newExpr);

    // Set function _XMP_coarray_rdma_do()
    funcId = _globalDecl.declExternFunc("_XMP_coarray_rdma_do");
    funcArgs = Xcons.List();
    if(leftExpr.Opcode() == Xcode.CO_ARRAY_REF){
      funcArgs.add(Xcons.IntConstant(XMPcoarray.PUT));
    }
    else{
      funcArgs.add(Xcons.IntConstant(XMPcoarray.GET));
    }

    // Get Coarray Descriptor
    funcArgs.add(Xcons.SymbolRef(coarray.getDescId()));

    // Get Local Pointer Name
    if(localExpr.Opcode() == Xcode.SUB_ARRAY_REF || localExpr.Opcode() == Xcode.ARRAY_REF){
      Xobject varAddr = localExpr.getArg(0);
      String varName = varAddr.getName();
      XMPcoarray localArray = _globalDecl.getXMPcoarray(varName, exprParentBlock);
      if(localArray == null){
	  funcArgs.add(varAddr);
	  Xobject XMP_NULL = Xcons.Cast(Xtype.voidPtrType, Xcons.IntConstant(0));  // ((void *)0)
	  funcArgs.add(XMP_NULL);
      }
      else{
	  funcArgs.add(Xcons.SymbolRef(_globalDecl.findVarIdent(XMP.COARRAY_ADDR_PREFIX_ + varAddr.getName())));
	  funcArgs.add(Xcons.SymbolRef(localArray.getDescId()));
      }
    }
    else if(localExpr.Opcode() == Xcode.VAR){
      String varName = localExpr.getName();
      XMPcoarray localVar = _globalDecl.getXMPcoarray(varName, exprParentBlock);
      if(localVar == null){
	Xobject varAddr = Xcons.AddrOf(localExpr);
	funcArgs.add(varAddr);
	Xobject XMP_NULL = Xcons.Cast(Xtype.voidPtrType, Xcons.IntConstant(0));  // ((void *)0)
	funcArgs.add(XMP_NULL);
      }
      else{
	funcArgs.add(Xcons.SymbolRef(_globalDecl.findVarIdent(XMP.COARRAY_ADDR_PREFIX_ + varName)));
	funcArgs.add(Xcons.SymbolRef(localVar.getDescId()));
      }
    }
    else if(localExpr.isConstant()){  // Fix me
      throw new XMPexception("Not supported a Constant Value at coarray Syntax");
    }
    else{
      throw new XMPexception("Not supported this coarray Syntax");
    }

    newExpr = funcId.Call(funcArgs);
    newExpr.setIsRewrittedByXmp(true);
    iter.insertStatement(newExpr);

    return null;
    // Memo: This function translates a coarray syntax (a[1:2:1]:[9] = b) into 4 functions.
    // This function returns null pointer except for shortcut functions. The reason of returning
    // null pointer, when XobjList is returned, an upper process is abort.
    // Therefore this function translates the coarray syntax directly.
  }

  private boolean is_stride_1(int dim, XobjList tripletList)
  {
    if(tripletList.getArg(dim).isConstant() || tripletList.getArg(dim).isVariable()){
        return true;
    } 
    else{
      Xobject stride = tripletList.getArg(dim).getArg(2);
      if(stride.isConstant()){
        if(stride.getInt() == 1){
          return true;
        }
      }
    }

    return false;
  }
  
  private boolean is_start_0(int dim, XobjList tripletList)
  {
    if(tripletList.getArg(dim).isVariable()){
      return false;
    }
    else if(tripletList.getArg(dim).isConstant())
      if(tripletList.getArg(dim).getInt() == 0){
        return true;
      }
      else{
        return false;
      }
    else{
      Xobject start = tripletList.getArg(dim).getArg(0);
      if(start.isConstant()){
        if(start.getInt() == 0){
          return true;
        }
      }
    }
    return false;
  }

  private boolean is_length_all(int dim, XobjList tripletList, Ident varId){
    if(tripletList.getArg(dim).isConstant() || tripletList.getArg(dim).isVariable()){
      return false;
    }

    Xtype varType = varId.Type();

    for(int i=0;i<dim;i++){
      varType = varType.getRef();
    }
    long dimSize = varType.getArraySize();
    Xobject length = tripletList.getArg(dim).getArg(1);

    Xobject arraySizeExpr = Xcons.List(Xcode.MINUS_EXPR, Xtype.intType,
                                       Xcons.IntConstant((int)dimSize),
                                       Xcons.IntConstant(0));

    if(arraySizeExpr.equals(length)){
      return true;
    }
    else if(length.Opcode() ==  Xcode.INT_CONSTANT){
      if(length.getInt() == (int)dimSize){
        return true;
      }
    }

    return false;
  }

  private boolean is_all_element(int dim, XobjList tripletList, Ident varId){
    if(is_start_0(dim, tripletList) && is_length_all(dim, tripletList, varId)){
      return true;
    }
    else{
      return false;
    }
  }

  private boolean is_length_1(int dim, XobjList tripletList)
  {
    if(tripletList.getArg(dim).isVariable() || tripletList.getArg(dim).isConstant()){
      return true;
    }
    else{
      Xobject length = tripletList.getArg(dim).getArg(1);
      if(length.isConstant()){
        if(length.getInt() == 1){
          return true;
        }
      }
    }

    return false;
  }

  private boolean isContinuousArray(Xobject myExpr, Block block) throws XMPexception
  {
    if(myExpr.Opcode() == Xcode.CO_ARRAY_REF)
      myExpr = myExpr.getArg(0);

    if(myExpr.Opcode() == Xcode.VAR || myExpr.Opcode() == Xcode.ARRAY_REF){
      return true;
    }
    else if(myExpr.Opcode() == Xcode.SUB_ARRAY_REF){
      XobjList tripletList = (XobjList)(myExpr).getArg(1);
      String arrayName = myExpr.getArg(0).getName();
      Ident varId = myExpr.findVarIdent(arrayName);
      Xtype varType = varId.Type();
      int varDim = varType.getNumDimensions();

      if(varDim == 1){
        if(!is_stride_1(0, tripletList)){
          return false;
        }
        else{
          return true;
        }
      }
      else if(varDim == 2){
        if(!is_stride_1(0, tripletList) || !is_stride_1(1, tripletList)){
          return false;
        }
        else if(is_all_element(1, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList)){
          return true;
        }
      }
      else if(varDim == 3){
        if(!is_stride_1(0, tripletList) || !is_stride_1(1, tripletList) || !is_stride_1(2, tripletList)){
          return false;
        }
        else if(is_all_element(1, tripletList, varId) && is_all_element(2, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_all_element(2, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_length_1(1, tripletList)){
          return true;
        }
      }
      else if(varDim == 4){
        if(!is_stride_1(0, tripletList) || !is_stride_1(1, tripletList) || !is_stride_1(2, tripletList)
           || !is_stride_1(3, tripletList)){
          return false;
        }
        else if(is_all_element(1, tripletList, varId) && is_all_element(2, tripletList, varId) 
                && is_all_element(3, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_all_element(2, tripletList, varId) && 
                is_all_element(3, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_length_1(1, tripletList) 
                && is_all_element(3, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_length_1(1, tripletList) && is_length_1(2, tripletList)){
          return true;
        }
      }
      else if(varDim == 5){
        if(!is_stride_1(0, tripletList) || !is_stride_1(1, tripletList) || !is_stride_1(2, tripletList)
           || !is_stride_1(3, tripletList) || !is_stride_1(4, tripletList)){
          return false;
        }
        else if(is_all_element(1, tripletList, varId) && is_all_element(2, tripletList, varId) 
                && is_all_element(3, tripletList, varId) && is_all_element(4, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_all_element(2, tripletList, varId) 
                && is_all_element(3, tripletList, varId) && is_all_element(4, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_length_1(1, tripletList)
                && is_all_element(3, tripletList, varId) && is_all_element(4, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_length_1(1, tripletList) && is_length_1(2, tripletList)
                && is_all_element(4, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_length_1(1, tripletList) && is_length_1(2, tripletList)
                && is_length_1(3, tripletList)){
          return true;
        }
      }
      else if(varDim == 6){
        if(!is_stride_1(0, tripletList) || !is_stride_1(1, tripletList) || !is_stride_1(2, tripletList)
           || !is_stride_1(3, tripletList) || !is_stride_1(4, tripletList) || !is_stride_1(5, tripletList)){
          return false;
        }
        else if(is_all_element(1, tripletList, varId) && is_all_element(2, tripletList, varId) 
                && is_all_element(3, tripletList, varId) && is_all_element(4, tripletList, varId) 
                && is_all_element(5, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_all_element(2, tripletList, varId) 
                && is_all_element(3, tripletList, varId) && is_all_element(4, tripletList, varId) 
                && is_all_element(5, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_length_1(1, tripletList) 
                && is_all_element(3, tripletList, varId) && is_all_element(4, tripletList, varId) 
                && is_all_element(5, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_length_1(1, tripletList) && is_length_1(2, tripletList)
                && is_all_element(4, tripletList, varId) && is_all_element(5, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_length_1(1, tripletList) && is_length_1(2, tripletList)
                && is_length_1(3, tripletList) && is_all_element(5, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_length_1(1, tripletList) && is_length_1(2, tripletList)
                && is_length_1(3, tripletList) && is_length_1(4, tripletList)){
          return true;
        }
      }
      else if(varDim == 7){
        if(!is_stride_1(0, tripletList) || !is_stride_1(1, tripletList) || !is_stride_1(2, tripletList)
           || !is_stride_1(3, tripletList) || !is_stride_1(4, tripletList) || !is_stride_1(5, tripletList)
           || !is_stride_1(6, tripletList)){
          return false;
        }
        else if(is_all_element(1, tripletList, varId) && is_all_element(2, tripletList, varId) 
                && is_all_element(3, tripletList, varId) && is_all_element(4, tripletList, varId) 
                && is_all_element(5, tripletList, varId) && is_all_element(6, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_all_element(2, tripletList, varId) 
                && is_all_element(3, tripletList, varId) && is_all_element(4, tripletList, varId) 
                && is_all_element(5, tripletList, varId) && is_all_element(6, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_length_1(1, tripletList) && 
                is_all_element(3, tripletList, varId) && is_all_element(4, tripletList, varId) 
                && is_all_element(5, tripletList, varId) && is_all_element(6, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_length_1(1, tripletList) && is_length_1(2, tripletList)
                && is_all_element(4, tripletList, varId) && is_all_element(5, tripletList, varId) 
                && is_all_element(6, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_length_1(1, tripletList) && is_length_1(2, tripletList)
                && is_length_1(3, tripletList) && is_all_element(5, tripletList, varId)
                && is_all_element(6, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_length_1(1, tripletList) && is_length_1(2, tripletList)
                && is_length_1(3, tripletList) && is_length_1(4, tripletList) 
                && is_all_element(6, tripletList, varId)){
          return true;
        }
        else if(is_length_1(0, tripletList) && is_length_1(1, tripletList) && is_length_1(2, tripletList)
                && is_length_1(3, tripletList) && is_length_1(4, tripletList) && is_length_1(5, tripletList)){
          return true;
        }
      }
    }
    else{
      throw new XMPexception("Not supported this coarray Syntax");
    }

    return false;
  }

  private boolean isCoarray(Xobject myExpr, Block block){
    if(myExpr.Opcode() == Xcode.ARRAY_REF || myExpr.Opcode() == Xcode.SUB_ARRAY_REF){
      myExpr = myExpr.getArg(0);
    }
    
    XMPcoarray coarray = _globalDecl.getXMPcoarray(myExpr.getSym(), block);
    
    if(coarray == null)
      return false;
    else
      return true;
  }
  
  private Xobject rewriteExpr(Xobject expr, Block block) throws XMPexception {
    if (expr == null) {
      return null;
    }
    switch (expr.Opcode()) {
    case ARRAY_REF:
      return rewriteArrayRef(expr, block);
    case VAR:
      return rewriteVarRef(expr, block, true);
    case ARRAY_ADDR:
      return rewriteVarRef(expr, block, false);
    case POINTER_REF:
      return rewritePointerRef(expr, block);
    default:
      {
	topdownXobjectIterator iter = new topdownXobjectIterator(expr);
	for (iter.init(); !iter.end(); iter.next()) {
	  Xobject myExpr = iter.getXobject();
	  if (myExpr == null) {
	    continue;
	  } else if (myExpr.isRewrittedByXmp()) {
	    continue;
	  }
	  switch (myExpr.Opcode()) {
	  case ARRAY_ADDR:
	    iter.setXobject(rewriteArrayAddr(myExpr, block));
	    break;
	  case ARRAY_REF:
	    iter.setXobject(rewriteArrayRef(myExpr, block));
	    break;
	  case SUB_ARRAY_REF:
	    System.out.println("sub_array_ref="+myExpr.toString());
	    break;
	  case XMP_DESC_OF:
	    iter.setXobject(rewriteXmpDescOf(myExpr, block));
	    break;
	  case VAR:
	    iter.setXobject(rewriteVarRef(myExpr, block, true));
	    break;
	  case POINTER_REF:
	    iter.setXobject(rewritePointerRef(myExpr, block));
	    break;
	  default:
	  }
	}
	return expr;
      }
    }
  }

  private Xobject rewriteXmpDescOf(Xobject myExpr, Block block) throws XMPexception {
    String entityName = myExpr.getArg(0).getName();
    XMPobject entity = _globalDecl.getXMPobject(entityName, block);
    Xobject e = null;

    if(entity != null){
      if(entity.getKind() == XMPobject.TEMPLATE || entity.getKind() == XMPobject.NODES){
	Ident XmpDescOfFuncId = _globalDecl.declExternFunc("_XMP_desc_of", myExpr.Type());
	e = XmpDescOfFuncId.Call(Xcons.List(entity.getDescId().Ref()));
      } 
      else{
	throw new XMPexception("Bad entity name for xmp_desc_of()");
      }
    }
    else{ // When myExpr is a distributed array name.
      String arrayName = myExpr.getArg(0).getSym();
      XMPalignedArray alignedArray =  _globalDecl.getXMPalignedArray(arrayName, block);
      if (alignedArray == null)
	throw new XMPexception(arrayName + " is not aligned global array or tempalte descriptor.");

      Ident XmpDescOfFuncId =  _globalDecl.declExternFunc("_XMP_desc_of", myExpr.Type());
      e = XmpDescOfFuncId.Call(Xcons.List(alignedArray.getDescId().Ref())); 
    }

    return e;
  }

  private Xobject rewriteArrayAddr(Xobject arrayAddr, Block block) throws XMPexception {
    XMPalignedArray alignedArray = _globalDecl.getXMPalignedArray(arrayAddr.getSym(), block);
    XMPcoarray coarray = _globalDecl.getXMPcoarray(arrayAddr.getSym(), block);

    if (alignedArray == null && coarray == null) {
      return arrayAddr;
    }

    else if(alignedArray != null && coarray == null){ // only alignedArray
      if (alignedArray.checkRealloc() || (alignedArray.isLocal() && !alignedArray.isParameter()) ||
	  alignedArray.isParameter()){
	Xobject newExpr = alignedArray.getAddrId().Ref();
	newExpr.setIsRewrittedByXmp(true);
	return newExpr;
      }
      else {
      	return arrayAddr;
      }
    } else if(alignedArray == null && coarray != null){  // only coarray
      return rewriteVarRef(arrayAddr, block, false);
    } else{ // no execute
      return arrayAddr;
    }
  }
  
  private Xobject rewriteVarRef(Xobject myExpr, Block block, boolean isVar) throws XMPexception {
    String varName     = myExpr.getSym();
    XMPalignedArray alignedArray = _globalDecl.getXMPalignedArray(varName, block);
    XMPcoarray coarray = _globalDecl.getXMPcoarray(varName, block);

    if (alignedArray != null && coarray == null){
      return alignedArray.getAddrId().Ref();
    }
    else if (alignedArray == null && coarray != null){
      Ident coarrayIdent = _globalDecl.getXMPcoarray(varName).getVarId();
      Ident localIdent = XMPlocalDecl.findLocalIdent(block, varName);
      if(coarrayIdent != localIdent){
        // e.g.) When an coarray is declared at global region and 
        //       the same name variable is decleard at local region.
        //
        // int a:[*]
        // void hoge(){
        //   int a;
        //   printf("%d\n", a);  <- "a" should not be changed.
        // }
        return myExpr;
      }
      else{
        Xobject newExpr = _globalDecl.findVarIdent(XMP.COARRAY_ADDR_PREFIX_ + varName).getValue();
        newExpr = Xcons.PointerRef(newExpr);
        if(isVar) // When coarray is NOT pointer,
          newExpr = Xcons.PointerRef(newExpr);
        return newExpr;
      }
    } else{
      return myExpr;
    }
  }
  
  private Xobject rewriteArrayRef(Xobject myExpr, Block block) throws XMPexception {
    Xobject arrayAddr = myExpr.getArg(0);
    String arrayName = arrayAddr.getSym();
    XMPalignedArray alignedArray = _globalDecl.getXMPalignedArray(arrayName, block);
    XMPcoarray      coarray      = _globalDecl.getXMPcoarray(arrayName, block);

    if (alignedArray == null && coarray == null) {
      return myExpr;
    } 
    else if(alignedArray != null && coarray == null){  // only alignedArray
      Xobject newExpr = null;
      XobjList arrayRefList = normArrayRefList((XobjList)myExpr.getArg(1), alignedArray);

      if (alignedArray.checkRealloc() || (alignedArray.isLocal() && !alignedArray.isParameter()) ||
	  alignedArray.isParameter()){
	newExpr = rewriteAlignedArrayExpr(arrayRefList, alignedArray);
      } 
      else {
        newExpr = Xcons.arrayRef(myExpr.Type(), arrayAddr, arrayRefList);
      }

      newExpr.setIsRewrittedByXmp(true);
      return newExpr;
    } 
    else if(alignedArray == null && coarray != null){  // only coarray
      Xobject newExpr = translateCoarrayRef(myExpr.getArg(1), coarray);
      if(isAddrCoarray((XobjList)myExpr.getArg(1), coarray) == true){
	return Xcons.AddrOf(newExpr);
      }	else{
	return newExpr;
      }
    } 
    else{  // this statemant must not be executed
      return myExpr;
    }
  }
  
  private Xobject rewritePointerRef(Xobject myExpr, Block block) throws XMPexception
  {
    Xobject addr_expr = myExpr.getArg(0);
    if (addr_expr.Opcode() == Xcode.PLUS_EXPR){

      Xobject pointer = addr_expr.getArg(0);
      Xobject offset = addr_expr.getArg(1);

      if (pointer.Opcode() == Xcode.VAR){
	XMPalignedArray alignedArray = _globalDecl.getXMPalignedArray(pointer.getSym(), block);
	XMPcoarray      coarray      = _globalDecl.getXMPcoarray(pointer.getSym(), block);

	if (alignedArray != null && coarray == null){
	  //if (!alignedArray.isParameter())
	    addr_expr.setArg(0, alignedArray.getAddrId().Ref());
	  // NOTE: an aligned pointer is assumed to be a one-dimensional array.
	  addr_expr.setArg(1, getCalcIndexFuncRef(alignedArray, 0, offset)); 
	}
	else if(alignedArray == null && coarray != null){
	  ;
	}
      }
    }
    return myExpr;
  }

  private boolean isAddrCoarray(XobjList myExpr, XMPcoarray coarray){
    if(myExpr.getArgOrNull(coarray.getVarDim()-1) == null){
      return true;
    }
    else{
      return false;
    }
  }

  private Xobject getCoarrayOffset(Xobject myExpr, XMPcoarray coarray){
    // "a[N][M][K]" is defined as a coarray.
    // If a[i][j][k] is referred, this function returns "(i * M * K) + (j * K) + (k)"
    if(myExpr.Opcode() == Xcode.VAR){
      return Xcons.Int(Xcode.INT_CONSTANT, 0);
    }

    Xobject newExpr = null;
    for(int i=0; i<coarray.getVarDim(); i++){
      Xobject tmp = null;
      for(int j=coarray.getVarDim()-1; j>i; j--){
        int size = coarray.getSizeAt(j);
        if(tmp == null){
          tmp = Xcons.Int(Xcode.INT_CONSTANT, size);
        } else{
          tmp = Xcons.binaryOp(Xcode.MUL_EXPR, Xcons.Int(Xcode.INT_CONSTANT, size), tmp);
        }
      } // end j

      /* Code may be optimized by native compiler when variable(e,g. i, j) is multipled finally. */
      if(myExpr.getArgOrNull(i) == null) break;
      Xobject var = myExpr.getArg(i);

      if(tmp != null){
        var = Xcons.binaryOp(Xcode.MUL_EXPR, tmp, var);
      }
      if(newExpr == null){
        newExpr = var.copy();
      } else{
        newExpr = Xcons.binaryOp(Xcode.PLUS_EXPR, newExpr, var);
      }
    }
    return newExpr;
  }

  private Xobject translateCoarrayRef(Xobject myExpr, XMPcoarray coarray){
    // "a[N][M][K]" is defined as a coarray.
    // When "a[i][j][k] = x;" is defined,
    // this function returns "*(_XMP_COARRAY_ADDR_a + (i * M * K) + (j * K) + (k)) = x;".
    Xobject newExpr = getCoarrayOffset(myExpr, coarray);
    
    int offset = -999;  // dummy
    if(newExpr.Opcode() == Xcode.INT_CONSTANT){
      offset = newExpr.getInt();
    }
    
    if(offset == 0){
      Ident tmpExpr = _globalDecl.findVarIdent(XMP.COARRAY_ADDR_PREFIX_ + coarray.getName());
      newExpr = Xcons.PointerRef(tmpExpr.Ref());
    }
    else{
      newExpr = Xcons.binaryOp(Xcode.PLUS_EXPR,
			       _globalDecl.findVarIdent(XMP.COARRAY_ADDR_PREFIX_ + coarray.getName()),
			       newExpr);
      newExpr = Xcons.PointerRef(newExpr);
    }
    
    return newExpr;
  }
  
  public static XobjList normArrayRefList(XobjList refExprList, XMPalignedArray alignedArray)
  {
    if (refExprList == null) {
      return null;
    } else {
      XobjList newRefExprList = Xcons.List();
      
      int arrayIndex = 0;
      for (Xobject x : refExprList) {
        Xobject normExpr = alignedArray.getAlignNormExprAt(arrayIndex);
        if (normExpr != null) {
          newRefExprList.add(Xcons.binaryOp(Xcode.PLUS_EXPR, x, normExpr));
        } else {
          newRefExprList.add(x);
        }
        arrayIndex++;
      }

      return newRefExprList;
    }
  }

  private Xobject rewriteAlignedArrayExpr(XobjList refExprList,
                                          XMPalignedArray alignedArray) throws XMPexception {
    int arrayDimCount = 0;
    XobjList args = Xcons.List(alignedArray.getAddrId().Ref());
    if (refExprList != null) {
      for (Xobject x : refExprList) {
	args.add(getCalcIndexFuncRef(alignedArray, arrayDimCount, x));
        arrayDimCount++;
      }
    }

    return createRewriteAlignedArrayFunc(alignedArray, arrayDimCount, args);
  }

  public static Xobject createRewriteAlignedArrayFunc(XMPalignedArray alignedArray, int arrayDimCount,
                                                      XobjList getAddrFuncArgs) throws XMPexception {
    int arrayDim = alignedArray.getDim();
    Ident getAddrFuncId = null;

    if (arrayDim < arrayDimCount) {
      throw new XMPexception("wrong array ref");
    } else if (arrayDim == arrayDimCount) {
      getAddrFuncId = XMP.getMacroId("_XMP_M_GET_ADDR_E_" + arrayDim, Xtype.Pointer(alignedArray.getType()));
      for (int i = 0; i < arrayDim - 1; i++)
        getAddrFuncArgs.add(alignedArray.getAccIdAt(i).Ref());
    } else {
      getAddrFuncId = XMP.getMacroId("_XMP_M_GET_ADDR_" + arrayDimCount, Xtype.Pointer(alignedArray.getType()));
      for (int i = 0; i < arrayDimCount; i++)
        getAddrFuncArgs.add(alignedArray.getAccIdAt(i).Ref());
    }

    Xobject retObj = getAddrFuncId.Call(getAddrFuncArgs);
    if (arrayDim == arrayDimCount) {
      return Xcons.PointerRef(retObj);
    } else {
      return retObj;
    }
  }

  private Xobject getCalcIndexFuncRef(XMPalignedArray alignedArray, int index, Xobject indexRef) throws XMPexception {
    switch (alignedArray.getAlignMannerAt(index)) {
      case XMPalignedArray.NOT_ALIGNED:
      case XMPalignedArray.DUPLICATION:
        return indexRef;
      case XMPalignedArray.BLOCK:
        if (alignedArray.hasShadow()) {
          XMPshadow shadow = alignedArray.getShadowAt(index);
          switch (shadow.getType()) {
            case XMPshadow.SHADOW_NONE:
            case XMPshadow.SHADOW_NORMAL:
              {
                XobjList args = Xcons.List(indexRef, alignedArray.getGtolTemp0IdAt(index).Ref());
                return XMP.getMacroId("_XMP_M_CALC_INDEX_BLOCK").Call(args);
              }
            case XMPshadow.SHADOW_FULL:
              return indexRef;
            default:
              throw new XMPexception("unknown shadow type");
          }
        }
        else {
          XobjList args = Xcons.List(indexRef,
                                     alignedArray.getGtolTemp0IdAt(index).Ref());
          return XMP.getMacroId("_XMP_M_CALC_INDEX_BLOCK").Call(args);
        }
      case XMPalignedArray.CYCLIC:
        if (alignedArray.hasShadow()) {
          XMPshadow shadow = alignedArray.getShadowAt(index);
          switch (shadow.getType()) {
            case XMPshadow.SHADOW_NONE:
              {
                XobjList args = Xcons.List(indexRef,
                                           alignedArray.getGtolTemp0IdAt(index).Ref());
                return XMP.getMacroId("_XMP_M_CALC_INDEX_CYCLIC").Call(args);
              }
            case XMPshadow.SHADOW_FULL:
              return indexRef;
            case XMPshadow.SHADOW_NORMAL:
              throw new XMPexception("only block distribution allows shadow");
            default:
              throw new XMPexception("unknown shadow type");
          }
        }
        else {
          XobjList args = Xcons.List(indexRef, alignedArray.getGtolTemp0IdAt(index).Ref());
          return XMP.getMacroId("_XMP_M_CALC_INDEX_CYCLIC").Call(args);
        }
      case XMPalignedArray.BLOCK_CYCLIC:
        {
          XMPtemplate t = alignedArray.getAlignTemplate();
          int ti = alignedArray.getAlignSubscriptIndexAt(index).intValue();
          XMPnodes n = t.getOntoNodes();
          int ni = t.getOntoNodesIndexAt(ti).getInt();

          if (alignedArray.hasShadow()) {
            XMPshadow shadow = alignedArray.getShadowAt(index);
            switch (shadow.getType()) {
              case XMPshadow.SHADOW_NONE:
                {
                  XobjList args = Xcons.List(indexRef, n.getSizeAt(ni), t.getWidthAt(ti));
                  return XMP.getMacroId("_XMP_M_CALC_INDEX_BLOCK_CYCLIC").Call(args);
                }
              case XMPshadow.SHADOW_FULL:
                return indexRef;
              case XMPshadow.SHADOW_NORMAL:
                throw new XMPexception("only block distribution allows shadow");
              default:
                throw new XMPexception("unknown shadow type");
            }
          }
          else {
            XobjList args = Xcons.List(indexRef, n.getSizeAt(ni), t.getWidthAt(ti));
            return XMP.getMacroId("_XMP_M_CALC_INDEX_BLOCK_CYCLIC").Call(args);
          }
        }
      case XMPalignedArray.GBLOCK:
        // XobjList args = Xcons.List(alignedArray.getDescId().Ref(), Xcons.IntConstant(index), indexRef);
        // Ident f = _globalDecl.declExternFunc("_XMP_lidx_GBLOCK");
        // return f.Call(args);

	if (alignedArray.hasShadow()) {
	  XMPshadow shadow = alignedArray.getShadowAt(index);
	  switch (shadow.getType()) {
	  case XMPshadow.SHADOW_NONE:
	  case XMPshadow.SHADOW_NORMAL:
	    {
	      XobjList args = Xcons.List(indexRef, alignedArray.getGtolTemp0IdAt(index).Ref());
	      return XMP.getMacroId("_XMP_M_CALC_INDEX_GBLOCK").Call(args);
	    }
	  case XMPshadow.SHADOW_FULL:
	    return indexRef;
	  default:
	    throw new XMPexception("unknown shadow type");
	  }
        }        
	else {
	  XobjList args = Xcons.List(indexRef,
				     alignedArray.getGtolTemp0IdAt(index).Ref());
	  return XMP.getMacroId("_XMP_M_CALC_INDEX_GBLOCK").Call(args);
	}
      default:
        throw new XMPexception("unknown align manner for array '" + alignedArray.getName()  + "'");
    }
  }

  public static void rewriteArrayRefInLoop(Xobject expr, XMPglobalDecl globalDecl, Block block) throws XMPexception {

    if (expr == null) return;

    topdownXobjectIterator iter = new topdownXobjectIterator(expr);
    for (iter.init(); !iter.end(); iter.next()) {
      Xobject myExpr = iter.getXobject();
      if (myExpr == null) {
        continue;
      } else if (myExpr.isRewrittedByXmp()) {
        continue;
      }
      switch (myExpr.Opcode()) {
        case ARRAY_REF:
          {
            Xobject arrayAddr = myExpr.getArg(0);
            String arrayName = arrayAddr.getSym();
	    XMPalignedArray alignedArray = globalDecl.getXMPalignedArray(arrayName, block);

            if (alignedArray != null) {
              Xobject newExpr = null;
              XobjList arrayRefList = XMPrewriteExpr.normArrayRefList((XobjList)myExpr.getArg(1), alignedArray);
              if (alignedArray.checkRealloc() || (alignedArray.isLocal() && !alignedArray.isParameter()) ||
		  alignedArray.isParameter()){
                newExpr = XMPrewriteExpr.rewriteAlignedArrayExprInLoop(arrayRefList, alignedArray);
              } else {
                newExpr = Xcons.arrayRef(myExpr.Type(), arrayAddr, arrayRefList);
              }
              newExpr.setIsRewrittedByXmp(true);
              iter.setXobject(newExpr);
            }
          } break;
        case POINTER_REF:
	  {
	    Xobject addr_expr = myExpr.getArg(0);
	    if (addr_expr.Opcode() == Xcode.PLUS_EXPR){

	      Xobject pointer = addr_expr.getArg(0);
	      Xobject offset = addr_expr.getArg(1);

	      if (pointer.Opcode() == Xcode.VAR){
		XMPalignedArray alignedArray = globalDecl.getXMPalignedArray(pointer.getSym(), block);
		if (alignedArray != null){
		  XobjList arrayRefList = XMPrewriteExpr.normArrayRefList(Xcons.List(offset), alignedArray);
		  if (alignedArray.checkRealloc() || (alignedArray.isLocal() && !alignedArray.isParameter()) ||
		      alignedArray.isParameter()){
		    Xobject newExpr = XMPrewriteExpr.rewriteAlignedArrayExprInLoop(arrayRefList, alignedArray);
		    newExpr.setIsRewrittedByXmp(true);
		    iter.setXobject(newExpr);
		  }
		  else {
		    addr_expr.setArg(1, arrayRefList.getArg(0));
		  }

		}
	      }
	    }
	    break;
	  }

        default:
      }
    }
  }

  private static Xobject rewriteAlignedArrayExprInLoop(XobjList refExprList,
                                                       XMPalignedArray alignedArray) throws XMPexception {
    int arrayDimCount = 0;
    XobjList args;

    args = Xcons.List(alignedArray.getAddrId().Ref());

    if (refExprList != null) {
      for (Xobject x : refExprList) {
        args.add(x);
        arrayDimCount++;
      }
    }

    return XMPrewriteExpr.createRewriteAlignedArrayFunc(alignedArray, arrayDimCount, args);
  }

  public static void rewriteLoopIndexInLoop(Xobject expr, String loopIndexName,
					    XMPtemplate templateObj, int templateIndex,
                                            XMPglobalDecl globalDecl, Block block) throws XMPexception {
    if (expr == null) return;
    topdownXobjectIterator iter = new topdownXobjectIterator(expr);
    for (iter.init(); !iter.end(); iter.next()) {
      Xobject myExpr = iter.getXobject();
      if (myExpr == null) {
        continue;
      } else if (myExpr.isRewrittedByXmp()) {
        continue;
      }
      switch (myExpr.Opcode()) {
      case VAR:
	{
	  if (loopIndexName.equals(myExpr.getSym())) {
	    iter.setXobject(calcLtoG(templateObj, templateIndex, myExpr));
	  }
	} break;
      case ARRAY_REF:
	{
	  XMPalignedArray alignedArray = globalDecl.getXMPalignedArray(myExpr.getArg(0).getSym(), block);
	  if (alignedArray == null) {
	    rewriteLoopIndexVar(templateObj, templateIndex, loopIndexName, myExpr);
	  } else {
	    myExpr.setArg(1, rewriteLoopIndexArrayRefList(templateObj, templateIndex, alignedArray,
							  loopIndexName, (XobjList)myExpr.getArg(1)));
	  }
	} break;
      case POINTER_REF:
	{
	  Xobject addr_expr = myExpr.getArg(0);
	  if (addr_expr.Opcode() == Xcode.PLUS_EXPR){

	    Xobject pointer = addr_expr.getArg(0);
	    Xobject offset = addr_expr.getArg(1);

	    if (pointer.Opcode() == Xcode.VAR){
	      XMPalignedArray alignedArray = globalDecl.getXMPalignedArray(pointer.getSym(), block);
	      if (alignedArray != null){
		  addr_expr.setArg(0, alignedArray.getAddrId().Ref());
		  addr_expr.setArg(1, rewriteLoopIndexArrayRef(templateObj, templateIndex, alignedArray, 0,
							       loopIndexName, offset));
	      }
	    }
	  }
	  break;
	}
      default:
      }
    }
  }

  private static void rewriteLoopIndexVar(XMPtemplate templateObj, int templateIndex,
                                          String loopIndexName, Xobject expr) throws XMPexception
  {
    topdownXobjectIterator iter = new topdownXobjectIterator(expr);
    for (iter.init(); !iter.end(); iter.next()) {
      Xobject myExpr = iter.getXobject();
      if (myExpr == null) {
        continue;
      } else if (myExpr.isRewrittedByXmp()) {
        continue;
      }
      switch (myExpr.Opcode()) {
      case VAR:
	{
	  if (loopIndexName.equals(myExpr.getString())) {
	    Xobject newExpr = calcLtoG(templateObj, templateIndex, myExpr);
	    iter.setXobject(newExpr);
	  }
	} break;
      default:
      }
    }
  }

  private static XobjList rewriteLoopIndexArrayRefList(XMPtemplate t, int ti, XMPalignedArray a,
                                                       String loopIndexName, XobjList arrayRefList) throws XMPexception
  {
    if (arrayRefList == null) {
      return null;
    }

    XobjList newArrayRefList = Xcons.List();
    int arrayDimIdx = 0;
    for (Xobject x : arrayRefList) {
      newArrayRefList.add(rewriteLoopIndexArrayRef(t, ti, a, arrayDimIdx, loopIndexName, x));
      arrayDimIdx++;
      x.setIsRewrittedByXmp(true);
    }

    return newArrayRefList;
  }

  private static Xobject rewriteLoopIndexArrayRef(XMPtemplate t, int ti, XMPalignedArray a, int ai,
                                                  String loopIndexName, Xobject arrayRef) throws XMPexception
  {
    if (arrayRef.Opcode() == Xcode.VAR) {
      if (loopIndexName.equals(arrayRef.getString())) {
        return calcShadow(t, ti, a, ai, arrayRef);
      } else {
        return arrayRef;
      }
    }

    topdownXobjectIterator iter = new topdownXobjectIterator(arrayRef);
    for (iter.init(); !iter.end(); iter.next()) {
      Xobject myExpr = iter.getXobject();
      if (myExpr == null) {
        continue;
      } else if (myExpr.isRewrittedByXmp()) {
        continue;
      }

      switch (myExpr.Opcode()) {
        case VAR:
          {
            if (loopIndexName.equals(myExpr.getString())) {
              iter.setXobject(calcShadow(t, ti, a, ai, myExpr));
            }
          } break;
        default:
      }
    }
    return arrayRef;
  }

  private static Xobject calcShadow(XMPtemplate t, int ti, XMPalignedArray a, int ai,
                                    Xobject expr) throws XMPexception {
    expr.setIsRewrittedByXmp(true);
    XMPtemplate alignedTemplate = a.getAlignTemplate();
    if (t != alignedTemplate) {
      throw new XMPexception("array '" + a.getName() + "' is aligned by template '" + alignedTemplate.getName() +
                             "'. loop is distributed by template '" + t.getName() + "'.");
    }

    if(a.getAlignSubscriptIndexAt(ai) != null){  // null is an asterisk
      if (ti != a.getAlignSubscriptIndexAt(ai).intValue()) {
	throw new XMPexception("array ref is not consistent with array alignment");
      }
    }

    XMPshadow shadow = a.getShadowAt(ai);
    switch (shadow.getType()) {
      case XMPshadow.SHADOW_NONE:
        return expr;
      case XMPshadow.SHADOW_NORMAL:
        return Xcons.binaryOp(Xcode.PLUS_EXPR, expr, shadow.getLo());
      case XMPshadow.SHADOW_FULL:
        return calcLtoG(t, ti, expr);
      default:
        throw new XMPexception("unknown shadow type");
    }
  }

  public static Xobject calcLtoG(XMPtemplate t, int ti, Xobject expr) throws XMPexception {
    expr.setIsRewrittedByXmp(true);

    if (!t.isDistributed()) {
      return expr;
    }

    XMPnodes n = t.getOntoNodes();
    int ni = -1;
    if (t.getDistMannerAt(ti) != XMPtemplate.DUPLICATION)
      ni = t.getOntoNodesIndexAt(ti).getInt();

    XobjList args = null;
    switch (t.getDistMannerAt(ti)) {
      case XMPtemplate.DUPLICATION:
        return expr;
      case XMPtemplate.BLOCK:
        // _XMP_M_LTOG_TEMPLATE_BLOCK(_l, _m, _N, _P, _p)
        args = Xcons.List(expr, t.getLowerAt(ti), t.getSizeAt(ti), n.getSizeAt(ni), n.getRankAt(ni));
        break;
      case XMPtemplate.CYCLIC:
        // _XMP_M_LTOG_TEMPLATE_CYCLIC(_l, _m, _P, _p)
        args = Xcons.List(expr, t.getLowerAt(ti), n.getSizeAt(ni), n.getRankAt(ni));
        break;
      case XMPtemplate.BLOCK_CYCLIC:
        // _XMP_M_LTOG_TEMPLATE_BLOCK_CYCLIC(_l, _b, _m, _P, _p)
        args = Xcons.List(expr, t.getWidthAt(ti), t.getLowerAt(ti), n.getSizeAt(ni), n.getRankAt(ni));
        break;
      case XMPtemplate.GBLOCK:
        // _XMP_M_LTOG_TEMPLATE_GBLOCK(_l, _m, _p)
	args = Xcons.List(expr, t.getDescId().Ref(), Xcons.IntConstant(ti));
	return XMP.getMacroId("_XMP_L2G_GBLOCK", Xtype.intType).Call(args);
      default:
        throw new XMPexception("unknown distribution manner");
    }
    return XMP.getMacroId("_XMP_M_LTOG_TEMPLATE_" + t.getDistMannerStringAt(ti), Xtype.intType).Call(args);
  }

  /*
   * rewrite OMP pragmas
   */
  private void rewriteOMPpragma(FunctionBlock fb, XMPsymbolTable localXMPsymbolTable)
  {
    topdownBlockIterator iter2 = new topdownBlockIterator(fb);

    for (iter2.init(); !iter2.end(); iter2.next()){
      Block block = iter2.getBlock();
      if (block.Opcode() == Xcode.OMP_PRAGMA){
	Xobject clauses = ((PragmaBlock)block).getClauses();
	if (clauses != null) rewriteOmpClauses(clauses, (PragmaBlock)block, fb, localXMPsymbolTable);
      }
    }
  }

  /*
   * rewrite OMP clauses
   */
  private void rewriteOmpClauses(Xobject expr, PragmaBlock pragmaBlock, Block block,
				 XMPsymbolTable localXMPsymbolTable)
  {
    bottomupXobjectIterator iter = new bottomupXobjectIterator(expr);
    
    for (iter.init(); !iter.end();iter.next()){
    	
      Xobject x = iter.getXobject();
      if (x == null)  continue;
      if (x.Opcode() == Xcode.VAR){
        try {
          iter.setXobject(rewriteArrayAddr(x, pragmaBlock));
        }
        catch (XMPexception e){
          XMP.error(x.getLineNo(), e.getMessage());
        }
      }
      else if (x.Opcode() == Xcode.LIST){
        if (x.left() != null && x.left().Opcode() == Xcode.STRING &&
            x.left().getString().equals("DATA_PRIVATE")){
          
          if (!pragmaBlock.getPragma().equals("FOR")) continue;
          
          XobjList itemList = (XobjList)x.right();
          
          // find loop variable
          Xobject loop_var = null;
          BasicBlockIterator i = new BasicBlockIterator(pragmaBlock.getBody());
          for (Block b = pragmaBlock.getBody().getHead(); b != null; b = b.getNext()){
            if (b.Opcode() == Xcode.F_DO_STATEMENT){
              loop_var = ((FdoBlock)b).getInductionVar();
            }
          }
          if (loop_var == null) continue;
          
          // check if the clause has contained the loop variable
          boolean flag = false;
          Iterator<Xobject> j = itemList.iterator();
          while (j.hasNext()){
            Xobject item = j.next();
            if (item.getName().equals(loop_var.getName())){
              flag = true;
            }
          }
          
          // add the loop variable to the clause
          if (!flag){
            itemList.add(loop_var);
          }
        }
      }
    }
  }
  
//  private Block makeDeviceLoop(BlockList body, XMPdevice device, Ident loop){
//    BlockList ret_body = Bcons.emptyBody();
//    Ident var = ret_body.declLocalIdent("_XACC_deviceloop_" + device.getName(), Xtype.intType);
//    Ident fid = _globalDecl.declExternFunc("_XACC_set_device_num");
//    
//    body.insert(fid.Call(Xcons.List(var.Ref(), device.getAccDevice().Ref())));
//    
//    Block deviceLoopBlock = Bcons.FORall(var.Ref(), device.getLower(), device.getUpper(),
//        device.getStride(), Xcode.LOG_LE_EXPR, body);
//    ret_body.add(deviceLoopBlock);
//    
//    return Bcons.COMPOUND(ret_body);
//  }
  
  /*
   * rewrite ACC pragmas
   */
  private void rewriteACCpragma(FunctionBlock fb, XMPsymbolTable localXMPsymbolTable){
    topdownBlockIterator bIter = new topdownBlockIterator(fb);

    for (bIter.init(); !bIter.end(); bIter.next()){
      Block block = bIter.getBlock();
      if (block.Opcode() == Xcode.ACC_PRAGMA){
        PragmaBlock pb = (PragmaBlock)block;
	Xobject clauses = pb.getClauses();
	
	ACCpragma pragma = ACCpragma.valueOf(((PragmaBlock)block).getPragma());
	BlockList newBody = Bcons.emptyBody();
	XMPdevice onDevice = null;
	XMPlayout layout = null;
	XMPon on = null;
	if (clauses != null){
	  onDevice = getXACCdevice((XobjList)clauses, fb);
	  layout = getXACClayout((XobjList)clauses);//, fb);
	  on = getXACCon((XobjList)clauses, fb);
	  //BlockList newBody = Bcons.emptyBody();
	  //rewriteACCClauses(clauses, (PragmaBlock)block, fb, localXMPsymbolTable, newBody);
	  if(!newBody.isEmpty() && !XMP.XACC){
	    bIter.setBlock(Bcons.COMPOUND(newBody));
	    newBody.add(Bcons.COMPOUND(Bcons.blockList(block))); //newBody.add(block);
	  }
	}
	

	if(XMP.XACC && onDevice != null){
	  if(pragma == ACCpragma.DATA || pragma == ACCpragma.PARALLEL_LOOP){
            Ident fid = _globalDecl.declExternFunc("acc_set_device_num");
            
            //base
            BlockList baseBody = Bcons.emptyBody();
            BlockList baseDeviceLoopBody = Bcons.emptyBody();
            Ident baseDeviceLoopVarId = baseBody.declLocalIdent("_XACC_device_" + onDevice.getName(), Xtype.intType);
            baseBody.add(Bcons.FORall(baseDeviceLoopVarId.Ref(), onDevice.getLower(), onDevice.getUpper(),
                onDevice.getStride(), Xcode.LOG_LE_EXPR, baseDeviceLoopBody));
            baseDeviceLoopBody.add(fid.Call(Xcons.List(baseDeviceLoopVarId.Ref(), onDevice.getDeviceRef())));
            rewriteACCClauses(clauses, pb, (Block)fb, localXMPsymbolTable, newBody, baseDeviceLoopBody, baseDeviceLoopVarId, onDevice, layout);
            BlockList pbBody;
            if(pragma == ACCpragma.DATA){
              pbBody = null;
            }else{
              pbBody = pb.getBody();
            }
            baseDeviceLoopBody.add(Bcons.PRAGMA(Xcode.ACC_PRAGMA, pb.getPragma(), clauses, pbBody));
            
            if(pragma == ACCpragma.DATA){
              BlockList beginBody = baseBody.copy();
              BlockList endBody = baseBody.copy();

              rewriteXACCPragmaData(beginBody, true);
              rewriteXACCPragmaData(endBody, false);

 
              newBody.add(Bcons.COMPOUND(beginBody));
              newBody.add(Bcons.COMPOUND(block.getBody()));	    
              newBody.add(Bcons.COMPOUND(endBody));
            }else{
              CforBlock forBlock = (CforBlock)pb.getBody().getHead();
              String loopVarName = forBlock.getInductionVar().getSym();

              try{
                int dim = on.getCorrespondingDim(loopVarName);
                if(dim >= 0){
                  XMPlayout myLayout = on.getLayout();
                  String layoutSym = XMPlayout.getDistMannerString(myLayout.getDistMannerAt(dim));
                  Ident loopInitId = baseDeviceLoopBody.declLocalIdent("_XACC_loop_init_" + loopVarName, Xtype.intType);
                  Ident loopCondId = baseDeviceLoopBody.declLocalIdent("_XACC_loop_cond_" + loopVarName, Xtype.intType);
                  Ident loopStepId = baseDeviceLoopBody.declLocalIdent("_XACC_loop_step_" + loopVarName, Xtype.intType);
                  Ident schedLoopFuncId = _globalDecl.declExternFunc("_XACC_sched_loop_layout_"+ layoutSym);
                  Xobject oldInit, oldCond, oldStep;
                  XobjList loopIter = XMPutil.getLoopIter(forBlock, loopVarName);
                  
                  //get old loop iter
                  if(loopIter != null){
                    oldInit = ((Ident)loopIter.getArg(0)).Ref();
                    oldCond = ((Ident)loopIter.getArg(1)).Ref();
                    oldStep = ((Ident)loopIter.getArg(2)).Ref();
                  }else{
                    oldInit = forBlock.getLowerBound();
                    oldCond = forBlock.getUpperBound();
                    oldStep = forBlock.getStep();
                  }
                  XobjList schedLoopFuncArgs = 
                      Xcons.List(oldInit,oldCond, oldStep,
                          loopInitId.getAddr(), loopCondId.getAddr(), loopStepId.getAddr(), 
								 on.getArrayDesc().Ref(), Xcons.IntConstant(dim), baseDeviceLoopVarId.Ref());
                  baseDeviceLoopBody.insert(schedLoopFuncId.Call(schedLoopFuncArgs));
                  
                  //rewrite loop iter
                  forBlock.setLowerBound(loopInitId.Ref());
                  forBlock.setUpperBound(loopCondId.Ref());
                  forBlock.setStep(loopStepId.Ref());
                }
                newBody.add(Bcons.COMPOUND(baseBody));
              } catch (XMPexception e) {
                XMP.error(pb.getLineNo(), e.getMessage());
              }
              
            }            
            bIter.setBlock(Bcons.COMPOUND(newBody));
	  }
          continue;
	}else{
	  rewriteACCClauses(clauses, (PragmaBlock)block, fb, localXMPsymbolTable, newBody, null, null, null, null);      
	}

	/*
	if (XMP.XACC && onDevice != null){
          Ident fid1 = _globalDecl.declExternFunc("_XMP_set_device_num");
	  if(pragma == ACCpragma.PARALLEL_LOOP){// || pragma == ACCpragma.DATA){
	    BlockList deviceLoop = Bcons.emptyBody();
	    Ident var = deviceLoop.declLocalIdent("_XACC_loop", Xtype.intType);
	    //Ident var = deviceLoop.declLocalIdent("_XACC_loop", Xtype.intType);
	    // Ident fid0 = _globalDecl.declExternFunc("xacc_get_num_current_devices",
	    // 					  Xtype.intType);

	    Block deviceLoopBlock = Bcons.FORall(var.Ref(), onDevice.getLower(), onDevice.getUpper(),
	        onDevice.getStride(), Xcode.LOG_LE_EXPR, newBody);

	    deviceLoop.add(deviceLoopBlock);
	    bIter.setBlock(Bcons.COMPOUND(deviceLoop));


	    newBody.insert(fid1.Call(Xcons.List(var.Ref(), onDevice.getAccDevice().Ref())));
	    newBody.add(Bcons.COMPOUND(Bcons.blockList(block)));
	  }else if(pragma == ACCpragma.DATA){
	    Ident var = newBody.declLocalIdent("_XACC_loop", Xtype.intType);
	    Block exitDataBlock;// = Bcons.emptyBlock();
	    Block enterDataBlock;// = Bcons.emptyBlock();
	    Xobject setDeviceCall = fid1.Call(Xcons.List(var.Ref(), onDevice.getAccDevice().Ref()));
	    
	    enterDataBlock = Bcons.PRAGMA(Xcode.ACC_PRAGMA, "update", clauses, null);
	    exitDataBlock = Bcons.PRAGMA(Xcode.ACC_PRAGMA, "update", Xcons.List(), null);
	    
	    BlockList deviceLoopEnterDataBody = Bcons.emptyBody();
	    deviceLoopEnterDataBody.add(setDeviceCall);
	    deviceLoopEnterDataBody.add(enterDataBlock);
	    
	    Block deviceLoopEnterData = Bcons.FORall(var.Ref(), onDevice.getLower(), onDevice.getUpper(),
                onDevice.getStride(), Xcode.LOG_LE_EXPR, deviceLoopEnterDataBody);
	    
	    BlockList deviceLoopExitDataBody = Bcons.emptyBody();
            deviceLoopExitDataBody.add(setDeviceCall);
            deviceLoopExitDataBody.add(exitDataBlock);
            
            Block deviceLoopExitData = Bcons.FORall(var.Ref(), onDevice.getLower(), onDevice.getUpper(),
                onDevice.getStride(), Xcode.LOG_LE_EXPR, deviceLoopExitDataBody);
            
            //XobjList clauses = pb. 
            
	    
	    bIter.setBlock(Bcons.COMPOUND(newBody));
	    newBody.add(deviceLoopEnterData);
	    newBody.add(Bcons.COMPOUND(block.getBody()));
	    newBody.add(deviceLoopExitData);
	  }
	}
*/
      }
    }
  }

  private void rewriteXACCPragmaData(BlockList body, Boolean isEnter) {
    PragmaBlock pb = null;
    
    BlockIterator iter = new topdownBlockIterator(body);
    for(iter.init(); !iter.end(); iter.next()){
      Block block = iter.getBlock();
      if(block.Opcode() == Xcode.ACC_PRAGMA){
        pb = (PragmaBlock)block;
      }
    }
    
    XobjList clauses = (XobjList)pb.getClauses();
    Block newPB = Bcons.PRAGMA(Xcode.ACC_PRAGMA, isEnter? "ENTER_DATA" : "EXIT_DATA", clauses, null);
    pb.setBody(Bcons.emptyBody());
    for(XobjArgs arg = clauses.getArgs(); arg != null; arg = arg.nextArgs()){
      Xobject clause = arg.getArg();
      if(clause == null) continue; 
      Xobject clauseArg = clause.right();
      String clauseName = clause.left().getName();
      ACCpragma pragma = ACCpragma.valueOf(clauseName);
      ACCpragma newClause = null;
      switch(pragma){
      case COPY:
        newClause = isEnter? ACCpragma.COPYIN : ACCpragma.COPYOUT;
        break;
      //case COPYIN:
        //newClause = isEnter? ACCpragma.COPYIN : ACCpragma.DELETE;
      default:
        
      }
      if(newClause != null){
		arg.setArg(Xcons.List(Xcons.String(newClause.toString()), clauseArg));
      }
    }
    pb.replace(newPB);
  }

  private XMPdevice getXACCdevice(XobjList clauses, Block block){
    XMPdevice onDevice = null;
    
    for(XobjArgs arg = clauses.getArgs(); arg != null; arg = arg.nextArgs()){
      Xobject x = arg.getArg();
      if(x == null) continue;
      String clauseName = x.left().getString();
      ACCpragma accClause = ACCpragma.valueOf(clauseName);
      //if(accClause == null) continue;
      if(accClause == ACCpragma.ON_DEVICE){
        String deviceName = x.right().getString();
        onDevice = (XMPdevice)_globalDecl.getXMPobject(deviceName, block);
        if (onDevice == null) XMP.error(x.getLineNo(), "wrong device in on_device");
        arg.setArg(null);
      }
    }
    return onDevice;
  }
  
  private XMPlayout getXACClayout(XobjList clauses){
    XMPlayout layout = null;
    
    for(XobjArgs arg = clauses.getArgs(); arg != null; arg = arg.nextArgs()){
      Xobject x = arg.getArg();
      if(x == null) continue;
      String clauseName = x.left().getString();
      ACCpragma accClause = ACCpragma.valueOf(clauseName);
      if(accClause == ACCpragma.LAYOUT){
        layout = new XMPlayout((XobjList)x.right());
        arg.setArg(null);
      }
    }
    
    return layout;
  }
  
  private XMPon getXACCon(XobjList clauses, Block b){
    //XMPlayout layout = null;
    XMPon on = null;
    
    for(XobjArgs arg = clauses.getArgs(); arg != null; arg = arg.nextArgs()){
      Xobject x = arg.getArg();
      if(x == null) continue;
      String clauseName = x.left().getString();
      ACCpragma accClause = ACCpragma.valueOf(clauseName);
      if(accClause == ACCpragma.ON){
        //layout = new XMPlayout((XobjList)x.right());
        XobjList clauseArg = (XobjList)x.right();
        XobjList array = (XobjList)clauseArg.getArg(0);
        XMPalignedArray alignedArray = _globalDecl.getXMPalignedArray(array.getArg(0).getSym());
        if(alignedArray == null){
          XMP.fatal("no aligned array");
        }
        on = new XMPon(array, alignedArray);
        arg.setArg(null);
      }
    }
    
    return on;
  }
  
  /*
   * rewrite ACC clauses
   */
  private XMPdevice rewriteACCClauses(Xobject expr, PragmaBlock pragmaBlock, Block block,
				      XMPsymbolTable localXMPsymbolTable, BlockList body, BlockList devLoopBody, Ident devLoopId, XMPdevice onDevice, XMPlayout layout){

//    XMPdevice onDevice = null;

    bottomupXobjectIterator iter = new bottomupXobjectIterator(expr);

    for (iter.init(); !iter.end(); iter.next()){
      Xobject x = iter.getXobject();
      if (x == null) continue;

      if (x.Opcode() == Xcode.LIST){
	if (x.left() == null || x.left().Opcode() != Xcode.STRING) continue;
	
	String clauseName = x.left().getString();
	ACCpragma accClause = ACCpragma.valueOf(clauseName); 
	if(accClause != null){
    	  switch(accClause){
    	  case HOST:
    	  case DEVICE:
    	  case USE_DEVICE:
    	  case PRIVATE:
    	  case FIRSTPRIVATE:   
    	  case DEVICE_RESIDENT:
    	    break;
	  default:
            if(!accClause.isDataClause()) continue;
    	  }
	  
	  XobjList itemList  = (XobjList)x.right();
	  for(int i = 0; i < itemList.Nargs(); i++){
	    Xobject item = itemList.getArg(i);
	    if(item.Opcode() == Xcode.VAR){
	      //item is variable or arrayAddr
	      try{
		itemList.setArg(i, rewriteACCArrayAddr(item, pragmaBlock, body, devLoopBody, devLoopId, onDevice, layout));
	      }catch (XMPexception e){
		XMP.error(x.getLineNo(), e.getMessage());
	      }
	    }else if(item.Opcode() == Xcode.LIST){
	      //item is arrayRef
	      try{
	        itemList.setArg(i, rewriteACCArrayRef(item, pragmaBlock, body));
	      }catch(XMPexception e){
	        XMP.error(x.getLineNo(), e.getMessage());
	      }
	    }
	  }
	}

	if (x.left().getString().equals("PRIVATE")){
	  if (!pragmaBlock.getPragma().contains("LOOP")) continue;

	  XobjList itemList = (XobjList)x.right();

	  // find loop variable
	  Xobject loop_var = null;
	  BasicBlockIterator i = new BasicBlockIterator(pragmaBlock.getBody());
	  for (Block b = pragmaBlock.getBody().getHead();
	      b != null;
	      b = b.getNext()){
	    if (b.Opcode() == Xcode.F_DO_STATEMENT){
	      loop_var = ((FdoBlock)b).getInductionVar();
	    }
	  }
	  if (loop_var == null) continue;

	  // check if the clause has contained the loop variable
	  boolean flag = false;
	  Iterator<Xobject> j = itemList.iterator();
	  while (j.hasNext()){
	    Xobject item = j.next();
	    if (item.getName().equals(loop_var.getName())){
	      flag = true;
	    }
	  }

	  // add the loop variable to the clause
	  if (!flag){
	    itemList.add(loop_var);
	    }
	}
      } 
      }
    return null;
  }
  
  private Xobject rewriteACCArrayAddr(Xobject arrayAddr, Block block, BlockList body, BlockList deviceLoopBody,
        Ident deviceLoopCounter, XMPdevice onDevice, XMPlayout layout) throws XMPexception {
      XMPalignedArray alignedArray = _globalDecl.getXMPalignedArray(arrayAddr.getSym(), block);
      XMPcoarray coarray = _globalDecl.getXMPcoarray(arrayAddr.getSym(), block);

      if (alignedArray == null && coarray == null) {
	  return arrayAddr;
      }
      else if(alignedArray != null && coarray == null){ // only alignedArray
	  if (alignedArray.checkRealloc() || (alignedArray.isLocal() && !alignedArray.isParameter()) ||
		  alignedArray.isParameter()){
	      Xobject arrayAddrRef = alignedArray.getAddrId().Ref();
	      Ident descId = alignedArray.getDescId();
	      XobjList arrayRef;
	      
	      if(deviceLoopCounter == null){
	      String arraySizeName = "_ACC_size_" + arrayAddr.getSym();
	      Ident arraySizeId = body.declLocalIdent(arraySizeName, Xtype.unsignedlonglongType);

	      Block getArraySizeFuncCall = _globalDecl.createFuncCallBlock("_XMP_get_array_total_elmts", Xcons.List(descId.Ref()));
	      body.insert(Xcons.Set(arraySizeId.Ref(), getArraySizeFuncCall.toXobject()));
	      
	      arrayRef = Xcons.List(arrayAddrRef, Xcons.List(Xcons.IntConstant(0), arraySizeId.Ref()));
	      }else{
	        String arraySizeName = "_XACC_size_" + arrayAddr.getSym();
	        String arrayOffsetName = "_XACC_offset_" + arrayAddr.getSym();
	        Ident arraySizeId = deviceLoopBody.declLocalIdent(arraySizeName, Xtype.unsignedlonglongType);
	        Ident arrayOffsetId = deviceLoopBody.declLocalIdent(arrayOffsetName, Xtype.unsignedlonglongType);
	        Block getRangeFuncCall = _globalDecl.createFuncCallBlock("_XACC_get_size", Xcons.List(descId.Ref(), arrayOffsetId.getAddr(), arraySizeId.getAddr(), deviceLoopCounter.Ref()));
	        deviceLoopBody.add(getRangeFuncCall);
	        arrayRef = Xcons.List(arrayAddrRef, Xcons.List(arrayOffsetId.Ref(), arraySizeId.Ref()));
	        /*	        
	        _XACC_init_device_array(_XMP_DESC_a, _XMP_DESC_d);
	        _XACC_split_device_array_BLOCK(_XMP_DESC_a, 0);
	        _XACC_calc_size(_XMP_DESC_a);
	        */
			if(layout != null){
	        Block initDeviceArrayFuncCall = _globalDecl.createFuncCallBlock("_XACC_init_device_array", Xcons.List(descId.Ref(), onDevice.getDescId().Ref()));
	        body.add(initDeviceArrayFuncCall);
	        for(int dim = alignedArray.getDim() - 1; dim >= 0; dim--){
	          String mannerStr = XMPlayout.getDistMannerString(layout.getDistMannerAt(dim));
	          Block splitDeviceArrayBlockCall = _globalDecl.createFuncCallBlock("_XACC_split_device_array_" + mannerStr, Xcons.List(descId.Ref(), Xcons.IntConstant(dim)));
	          body.add(splitDeviceArrayBlockCall);
	        }
	        Block calcDeviceArraySizeCall = _globalDecl.createFuncCallBlock("_XACC_calc_size", Xcons.List(descId.Ref()));
                body.add(calcDeviceArraySizeCall);
                alignedArray.setLayout(layout);
			}
	      }
	      
	      arrayRef.setIsRewrittedByXmp(true);
	      return arrayRef;
	  }
	  else {
	      return arrayAddr;
	  }
      } else if(alignedArray == null && coarray != null){  // only coarray
          Xobject coarrayAddrRef = _globalDecl.findVarIdent(XMP.COARRAY_ADDR_PREFIX_ + arrayAddr.getSym()).Ref();
	  Ident descId = coarray.getDescId();
	  
	  String arraySizeName = "_ACC_size_" + arrayAddr.getSym();
          Ident arraySizeId = body.declLocalIdent(arraySizeName, Xtype.unsignedlonglongType);
	  
          Block getArraySizeFuncCall = _globalDecl.createFuncCallBlock("_XMP_get_array_total_elmts", Xcons.List(descId.Ref()));
          body.insert(Xcons.Set(arraySizeId.Ref(), getArraySizeFuncCall.toXobject()));
          
          XobjList arrayRef = Xcons.List(coarrayAddrRef, Xcons.List(Xcons.IntConstant(0), arraySizeId.Ref()));
          
          arrayRef.setIsRewrittedByXmp(true);
          return arrayRef;
      } else{ // no execute
	  return arrayAddr;
      }
  }
  
  private Xobject rewriteACCArrayRef(Xobject arrayRef, Block block, BlockList body) throws XMPexception {
      Xobject arrayAddr = arrayRef.getArg(0);
      
      XMPalignedArray alignedArray = _globalDecl.getXMPalignedArray(arrayAddr.getSym(), block);
      XMPcoarray coarray = _globalDecl.getXMPcoarray(arrayAddr.getSym(), block);
      
      if(alignedArray != null && coarray == null){ //only alignedArray
          Xobject alignedArrayAddrRef = alignedArray.getAddrId().Ref();
          arrayRef.setArg(0, alignedArrayAddrRef);
      }else if(alignedArray == null && coarray != null){ //only coarray
          Xobject coarrayAddrRef = _globalDecl.findVarIdent(XMP.COARRAY_ADDR_PREFIX_ + arrayAddr.getSym()).Ref();
          arrayRef.setArg(0, coarrayAddrRef);
      }
      return arrayRef;
  }
}
