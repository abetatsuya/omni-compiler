static char rcsid[] = "$Id$";
/* 
 * $TSUKUBA_Release: Omni OpenMP Compiler 3 $
 * $TSUKUBA_Copyright:
 *  PLEASE DESCRIBE LICENSE AGREEMENT HERE
 *  $
 */
/* error case of private 007 :
 * shared と private に同じ変数が宣言された場合の動作確認
 */

#include <omp.h>


int	errors = 0;
int	thds;


int	prvt;


main ()
{
  int	i;


  thds = omp_get_max_threads ();
  if (thds == 1) {
    printf ("should be run this program on multi threads.\n");
    exit (0);
  }
  omp_set_dynamic (0);


  #pragma omp parallel for private (prvt) shared (prvt)
  for (i=0;  i<thds;  i++) {
    prvt = omp_get_thread_num ();
  }


  printf ("err_private 007 : FAILED, can not compile this program.\n");
  return 1;
}
