      PROGRAM main
        USE ISO_FORTRAN_ENV
        INTEGER(ATOMIC_INT_KIND) :: I[10,*]
        CALL ATOMIC_DEFINE(I, 1)
      END PROGRAM main
