program main
    integer, external::omp_get_num_threads
    print *, omp_get_num_threads()
    !$omp single
    print *, "in single"
    !$omp end single
end program

