#include "mpi-ext.h"

void _XMP_fjrdma_initialize(int argc, char **argv)
{
  int ret = FJMPI_Rdma_init();
  if(ret) _XMP_fatal("FJMPI_Rdma_init error!");
}

void _XMP_fjrdma_finalize()
{
  int ret = FJMPI_Rdma_finalize();
  if(ret) _XMP_fatal("FJMPI_Rdma_init error!");
}
