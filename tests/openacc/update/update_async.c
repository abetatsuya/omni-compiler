#include <stdio.h>

int main()
{
  int array_1d[10];
  int array_2d[10][20];
  int i,j;

  for(i=0;i<10;i++){
    array_1d[i] = 0;
    for(j=0;j<20;j++){
      array_2d[i][j] = 0;
    }
  }

#pragma acc data copyin(array_1d, array_2d)
  {
#pragma acc parallel loop
    for(i=0;i<10;i++)
      array_1d[i] = i;

#pragma acc update host(array_1d) async
    
#pragma acc parallel loop collapse(2)
    for(i=0;i<10;i++){
      for(j=0;j<20;j++){
	array_2d[i][j] = i*10+j;
      }
    }

#pragma acc update host(array_2d) async(4)
#pragma acc wait
  }

  //check
  for(i=0;i<10;i++){
    if(array_1d[i] != i) return 1;
  }

  for(i=0;i<10;i++){
    for(j=0;j<20;j++){
      if(array_2d[i][j] != i*10+j) return 2;
    }
  }

  printf("PASS\n");
  return 0;
}
