static char rcsid[] = "$Id$";
/* 
 * $TSUKUBA_Release: Omni OpenMP Compiler 3 $
 * $TSUKUBA_Copyright:
 *  PLEASE DESCRIBE LICENSE AGREEMENT HERE
 *  $
 */
/* error case of threadprivate 007 :
 * threadprivate された変数が reduction に使えないことを確認
 */

#include <omp.h>


int	errors = 0;
int	thds;


int	x;
#pragma omp threadprivate(x)


main ()
{
  thds = omp_get_max_threads ();
  if (thds == 1) {
    printf ("should be run this program on multi threads.\n");
    exit (0);
  }
  omp_set_dynamic (0);


  #pragma omp parallel reduction(+:x)
  {
    int	id = omp_get_thread_num ();

    x += id;
  }


  printf ("err_threadprivate 007 : FAILED, can not compile this program.\n");
  return 1;
}
