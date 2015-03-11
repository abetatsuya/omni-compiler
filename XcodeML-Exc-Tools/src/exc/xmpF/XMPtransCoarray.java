package exc.xmpF;

import exc.object.*;
import exc.block.*;
import java.util.*;

/*
 * Translate Fortran Coarray
 */
public class XMPtransCoarray 
{
  private Boolean DEBUG = false;       // change me in debugger

  // constants
  final static String DESCRIPTOR_PREFIX  = "xmpf_codescr";
  final static String CRAYPOINTER_PREFIX = "xmpf_crayptr";
  final static String COARRAYSIZE_PREFIX = "xmpf_traverse_coarraysize";
  final static String COARRAYINIT_PREFIX = "xmpf_traverse_initcoarray";

  static ArrayList<XMPtransCoarray> ancestors
    = new ArrayList<XMPtransCoarray>();

  private XMPenv env;

  private String name;

  private FuncDefBlock funcDef;
  private XobjectDef def;
  private FunctionBlock fblock;

  private Vector<XMPcoarray> localCoarrays;
  private Vector<XMPcoarray> useAssociatedCoarrays;
  private Vector<XMPcoarray> visibleCoarrays;

  //private XMPinitProcedure initProcedure;
  private String initProcTextForFile;

  private String sizeProcName, initProcName;
  private String commonName1, commonName2;

  //------------------------------------------------------------
  //  CONSTRUCTOR
  //------------------------------------------------------------
  public XMPtransCoarray(FuncDefBlock funcDef, XMPenv env) {
    this.funcDef = funcDef;
    def = funcDef.getDef();
    fblock = funcDef.getBlock();
    this.env = env;
    env.setCurrentDef(funcDef);      // needed if this is called before XMPrewriteExpr ???
    name = fblock.getName();
    String postfix = genNewProcPostfix();
    sizeProcName = COARRAYSIZE_PREFIX + postfix;
    initProcName = COARRAYINIT_PREFIX + postfix;
    commonName1 = DESCRIPTOR_PREFIX + "_" + name;
    commonName2 = CRAYPOINTER_PREFIX + "_" + name;

    _setCoarrays();
    _check_ifIncludeXmpLib();

    XMP.exitByError();   // exit if error has found.
  }

  private void _setCoarrays() {
    // set localCoarrays as coarrays declared in the current procedure
    // set useAssociatedCoarrays as coarrays declared in using modules
    _setLocalCoarrays();

    // renew the list of the current hosts
    // (assuming top-down analysis)
    XobjectDef pdef = def.getParent();
    if (pdef == null) {
      // I have no host procedure.  I.e., I am an external procedure.
      ancestors.clear();
    } else {
      for (int i = ancestors.size() - 1; i >= 0; i--) {
        if (pdef == ancestors.get(i).def) {
          // found the host (my parent) procedure
          break;
        }
        ancestors.remove(i);
      }
    }
    ancestors.add(this);

    // set all coarrays declared in the current and the host procedures
    visibleCoarrays = new Vector<XMPcoarray>();
    visibleCoarrays.addAll(localCoarrays);
    visibleCoarrays.addAll(useAssociatedCoarrays);
    if (ancestors.size() > 1) {
      // host association
      XMPtransCoarray host = ancestors.get(ancestors.size() - 2);
      visibleCoarrays.addAll(host.visibleCoarrays);
    }
  }


  private void _setLocalCoarrays() {
    localCoarrays = new Vector<XMPcoarray>();
    useAssociatedCoarrays = new Vector<XMPcoarray>();

    Xobject idList = def.getFuncIdList();
    for (Xobject obj: (XobjList)idList) {
      Ident ident = (Ident)obj;
      if (ident.wasCoarray()) {
        // found it is a coarray or a variable converted from a coarray
        XMPcoarray coarray = new XMPcoarray(ident, funcDef, env);
        if (coarray.isUseAssociated())
          useAssociatedCoarrays.add(coarray);
        else
          localCoarrays.add(coarray);
      }
    }
  }


  //------------------------------------------------------------
  //  TRANSLATION
  //------------------------------------------------------------
  /*
      convert from:
      --------------------------------------------
        subroutine EX1
          real :: V1(10,20)[4,*]
          complex(8) :: V2[*]
          integer, allocatable :: V3(:)[:,:]
          ...
          V1(1:3,j)[k1,k2] = (/1.0,2.0,3.0/)
          z = V2[k]**2
          n(1:5) = V3(2:10:2)[k1,k2]
        end subroutine
      --------------------------------------------
      to:
      --------------------------------------------
        subroutine EX1
          real :: V1(1:10,1:20)                                ! f
          complex(8) :: V2                                     ! f
          integer, pointer :: V3(:)[:,:]                       ! f,h
          integer :: CD_V1                                     ! a
          integer :: CD_V2                                     ! a
          integer :: CD_V3                                     ! a
          pointer (CP_V1, V1)                                  ! a
          pointer (CP_V2, V2)                                  ! a
          pointer (CP_V3, V3)                                  ! a
          common /xmpf_CD_EX1/ CD_V1, CD_V2                    ! g
          common /xmpf_CP_EX1/ CP_V1, CP_V2                    ! g
          ...
          call xmpf_coarray_put(CD_V1, V1(1,j), 4, &
            k1+4*(k2-1), (/1.0,2.0,3.0/), ...)                 ! d
          z = xmpf_coarray_get0d(CD_V2, V2, 16, k, 0) ** 2     ! e
          n(1:5) = xmpf_coarray_get1d(CD_V3, V3(2), 4, &
            k1+4*(k2-1), ...)                                  ! e
        end subroutine

        subroutine xmpf_traverse_coarraysize_ex1               ! b
          call xmpf_coarray_count_size(200, 4)
          call xmpf_coarray_count_size(1, 16)
        end subroutine

        subroutine xmpf_traverse_initcoarray_ex1               ! b
          integer :: CD_V1
          integer :: CD_V2
          integer(8) :: CP_V1
          integer(8) :: CP_V2
          common /xmpf_CD_EX1/ CD_V1, CD_V2
          common /xmpf_CP_EX1/ CP_V1, CP_V2
          call coarray_get_share(CD_V1, CP_V1, 200, 4)
          call coarray_get_share(CD_V2, CP_V2, 1, 16)
        end subroutine
      --------------------------------------------
        CD_Vn: serial number for descriptor of Vn
        CP_Vn: cray poiter pointing to Vn
  */

  public void run() {
    // error check for each coarray declaration
    for (XMPcoarray coarray: localCoarrays)
      coarray.errorCheck();

    // select static local coarrays
    Vector<XMPcoarray> staticLocalCoarrays = new Vector<XMPcoarray>();
    Vector<XMPcoarray> allocatableLocalCoarrays = new Vector<XMPcoarray>();
    for (XMPcoarray coarray: localCoarrays) {
      if (coarray.isAllocatable())
        allocatableLocalCoarrays.add(coarray);
      else if (!coarray.isDummyArg())
        staticLocalCoarrays.add(coarray);
    }

    // a. declare cray-pointers and descriptors (static coarrays only)
    genDeclOfCrayPointer(staticLocalCoarrays);

    // g. generate common stmt (static coarrays only)
    genCommonStmt(commonName1, commonName2, staticLocalCoarrays, def);

    // e. replace coindexed objects with function references
    replaceCoindexObjs(visibleCoarrays);

    // d. replace coindexed variable assignment stmts with call stmts
    replaceCoindexVarStmts(visibleCoarrays);

    // b. generate allocation into init procedure (static coarrays only)
    genAllocOfStaticCoarrays(staticLocalCoarrays);

    // c. convert allocate stmt for allocatable coarrays
    //    (not supported yet)
    //genAllocOfAllocCoarrays(localCoarrays);

    // f. remove codimensions from declarations of coarrays
    removeCodimensionsFromCoarrays(localCoarrays);

    // h. replace allocatable attributes with pointer attributes
    //    (allocatable coarrays only)
    replaceAllocatableWithPointer(allocatableLocalCoarrays);
  }


  //-----------------------------------------------------
  //  TRANSLATION a.
  //  declare cray-pointers and descriptors 
  //-----------------------------------------------------
  //
  private void genDeclOfCrayPointer(Vector<XMPcoarray> coarrays) {
    // declare idents of cray pointers & descriptors
    for (XMPcoarray coarray: coarrays)
      coarray.declareIdents(CRAYPOINTER_PREFIX, DESCRIPTOR_PREFIX);
  }


  //-----------------------------------------------------
  //  TRANSLATION g.
  //  generate common stmt in this procedure
  //-----------------------------------------------------
  //
  private void genCommonStmt(String commonName1, String commonName2,
                             Vector<XMPcoarray> coarrays, XobjectDef def) {
    // do nothing if no coarrays are declared.
    if (coarrays.isEmpty())
      return;

    // common block name
    Xobject cnameObj1 = Xcons.Symbol(Xcode.IDENT, commonName1);
    Xobject cnameObj2 = Xcons.Symbol(Xcode.IDENT, commonName2);

    // list of common vars
    Xobject varList1 = Xcons.List();
    Xobject varList2 = Xcons.List();
    for (XMPcoarray coarray: coarrays) {
      Ident descrId = coarray.getDescriptorId();
      Ident cptrId = coarray.getCrayPointerId();
      varList1.add(Xcons.FvarRef(descrId));
      varList2.add(Xcons.FvarRef(cptrId));
    }

    // declaration 
    Xobject decls = fblock.getBody().getDecls();
    decls.add(Xcons.List(Xcode.F_COMMON_DECL,
                         Xcons.List(Xcode.F_VAR_LIST, cnameObj1, varList1)));
    decls.add(Xcons.List(Xcode.F_COMMON_DECL,
                         Xcons.List(Xcode.F_VAR_LIST, cnameObj2, varList2)));
  }


  //-----------------------------------------------------
  //  TRANSLATION d. (PUT)
  //  convert statements whose LHS are coindexed variables
  //  to subroutine calls
  //-----------------------------------------------------
  private void replaceCoindexVarStmts(Vector<XMPcoarray> coarrays) {
    BlockIterator bi = new topdownBlockIterator(fblock);

    for (bi.init(); !bi.end(); bi.next()) {

      BasicBlock bb = bi.getBlock().getBasicBlock();
      if (bb == null) continue;
      for (Statement s = bb.getHead(); s != null; s = s.getNext()) {
        Xobject assignExpr = s.getExpr();
        if (assignExpr == null)
          continue;

        if (_isCoindexVarStmt(assignExpr)) {
          // found -- convert the statement
          Xobject callExpr = coindexVarStmtToCallStmt(assignExpr, coarrays);
          //s.insert(callExpr);
          //s.remove();
          s.setExpr(callExpr);
        }
      }
    }
  }


  private Boolean _isCoindexVarStmt(Xobject xobj) {
    if (xobj.Opcode() == Xcode.F_ASSIGN_STATEMENT) {
      Xobject lhs = xobj.getArg(0);
      if (lhs.Opcode() == Xcode.CO_ARRAY_REF)
        return true;
    }
    return false;
  }


  /*
   * convert a statement:
   *    v(s1,s2,...)[cs1,cs2,...] = rhs
   * to:
   *    external :: PutCommLibName
   *    call PutCommLibName(..., rhs)
   */
  private Xobject coindexVarStmtToCallStmt(Xobject assignExpr,
                                         Vector<XMPcoarray> coarrays) {
    Xobject lhs = assignExpr.getArg(0);
    Xobject rhs = assignExpr.getArg(1);

    int condition = _getConditionOfCoarrayPut(rhs);

    XMPcoindexObj coindexObj = new XMPcoindexObj(lhs, coarrays);
    return coindexObj.toCallStmt(rhs, Xcons.IntConstant(condition));
  }

  /*
   * condition 1: It may be necessary to use buffer copy.
   *              The address of RHS may not be accessed by FJ-RDMA.
   * condition 0: Otherwise.
   */
  private int _getConditionOfCoarrayPut(Xobject rhs) {
    if (rhs.isConstant())
      return 1;

    if (rhs.Opcode() == Xcode.F_ARRAY_CONSTRUCTOR)
      return 1;

    return 0;
  }

  //-----------------------------------------------------
  //  TRANSLATION e. (GET)
  //  convert coindexed objects to function references
  //-----------------------------------------------------
  private void replaceCoindexObjs(Vector<XMPcoarray> coarrays) {
    // itaration to solve nested reference of coindexed object.
    while (_replaceCoindexObjs1(coarrays));
  }

  private Boolean _replaceCoindexObjs1(Vector<XMPcoarray> coarrays) {
    XobjectIterator xi = new topdownXobjectIterator(def.getFuncBody());

    Boolean done = false;
    for (xi.init(); !xi.end(); xi.next()) {
      Xobject xobj = xi.getXobject();
      if (xobj == null)
        continue;

      if (xobj.Opcode() == Xcode.CO_ARRAY_REF) {
        Xobject parent = (Xobject)xobj.getParent();

        if (parent.Opcode() == Xcode.F_ASSIGN_STATEMENT &&
            parent.getArg(0) == xobj)
          // found a coindexed variable, which is LHS of an assignment stmt.
          continue;  // do nothing 

        // found target to convert
        Xobject funcCall = coindexObjToFuncRef(xobj, coarrays);
        xi.setXobject(funcCall);
        done = true;
      }
    }

    return done;
  }

  /*
   * convert expression:
   *    v(s1,s2,...)[cs1,cs2,...]
   * to:
   *    type,external,dimension(:,:,..) :: commGetLibName_M
   *    commGetLibName_M(...)
   */
  private Xobject coindexObjToFuncRef(Xobject funcRef,
                                    Vector<XMPcoarray> coarrays) {
    XMPcoindexObj coindexObj = new XMPcoindexObj(funcRef, coarrays);
    return coindexObj.toFuncRef();
  }


  //-----------------------------------------------------
  //  TRANSLATION b.
  //  generate allocation of static coarrays
  //-----------------------------------------------------
  // and generate and add an initialization routine into the
  // same file (see XMPcoarrayInitProcedure)
  //
  private void genAllocOfStaticCoarrays(Vector<XMPcoarray> coarrays) {
    // do nothing if no coarrays are declared.
    if (coarrays.isEmpty())
      return;

    // output init procedure
    XMPcoarrayInitProcedure coarrayInit = 
      new XMPcoarrayInitProcedure(coarrays, sizeProcName, initProcName,
                                  commonName1, commonName2, env);
    coarrayInit.run();
  }


  //-----------------------------------------------------
  //  TRANSLATION c.
  //  convert allocate-stmt for allocatable coarrays
  //-----------------------------------------------------
  private void genAllocOfAllocCoarrays(Vector<XMPcoarray> coarrays) {
    // do nothing if no coarrays are declared.
    if (coarrays.isEmpty())
      return;

    for (XMPcoarray coarray: coarrays)
      if (coarray.isAllocatable())
        ;
        //XMP.error("Not supported: allocatable coarry: "+coarray.getName());
  }



  //-----------------------------------------------------
  //  TRANSLATION f.
  //  remove codimensions from declaration of coarray
  //-----------------------------------------------------
  //
  private void removeCodimensionsFromCoarrays(Vector<XMPcoarray> coarrays) {
    // remove codimensions form coarray declaration
    for (XMPcoarray coarray: coarrays)
      coarray.hideCodimensions();
  }

  //-----------------------------------------------------
  //  TRANSLATION h.
  //  replace allocatable attributes with pointer attributes
  //-----------------------------------------------------
  //
  private void replaceAllocatableWithPointer(Vector<XMPcoarray> coarrays) {
    // remove codimensions form coarray declaration
    for (XMPcoarray coarray: coarrays) {
      coarray.resetAllocatable();
      coarray.setPointer();
    }
  }


  //-----------------------------------------------------
  //  parts
  //-----------------------------------------------------
  private String genNewProcPostfix() {
    return genNewProcPostfix(getHostNames());
  }

  private String genNewProcPostfix(String ... names) { // host-to-guest order
    int n = names.length;
    String procPostfix = "";
    for (int i = 0; i < n; i++) {
      procPostfix += "_";
      StringTokenizer st = new StringTokenizer(names[i], "_");
      int n_underscore = st.countTokens() - 1;
      if (n_underscore > 0)   // '_' was found in names[i]
        procPostfix += String.valueOf(n_underscore);
      procPostfix += names[i];
    }
    return procPostfix;
  }

  private String[] getHostNames() {
    Vector<String> list = new Vector();
    list.add(def.getName());
    XobjectDef parentDef = def.getParent();
    while (parentDef != null) {
      list.add(parentDef.getName());
      parentDef = parentDef.getParent();
    }

    int n = list.size();
    String[] names = new String[n];
    for (int i = 0; i < n; i++)
      names[i] = list.get(n-i-1);

    return names;
  }


  //------------------------------------------------------------
  //  ERROR CHECKING
  //------------------------------------------------------------

  /*
   * Detect error if a coarray exists and xmp_lib.h is not included.
   */
  private void _check_ifIncludeXmpLib() {
    
    if (!_isCoarrayReferred() && !_isCoarrayIntrinsicUsed()) {
      /* any coarray features are not used */
      return;
    }

    /* check a typical name defined in xmp_lib.h */
    Ident id = def.findIdent("xmpf_coarray_get0d");
    if (id == null) {
      /* xmpf_lib.h seems not included. */
      XMP.error("current restriction: \"include \'xmp_lib.h\'\" is " +
                "necessary to use any coarray features.");
    }
  }

  private boolean _isCoarrayReferred() {
    if (localCoarrays.isEmpty())
      return false;
    return true;
  }

  private boolean _isCoarrayIntrinsicUsed() {
    final String[] _coarrayIntrinsics = {
      "xmpf_sync_all",
      "xmpf_sync_images", 
      "xmpf_lock",
      "xmpf_unlock",
      "xmpf_critical",
      "xmpf_end_critical",
      "xmpf_sync_memory",
      "xmpf_error_stop",
      };
    final List coarrayIntrinsics = 
      Arrays.asList(_coarrayIntrinsics);

    XobjList identList = def.getDef().getIdentList();
    for (Xobject x: identList) {
      Ident id = (Ident)x;
      if (coarrayIntrinsics.contains(id.getName()))
        return true;
    }
    return false;
  }

}

