obj-m += kgsl_nowait.o

# Through Android's hook in kvmalloc() as 6.12 has it. Linux 6.16 and later
# need no module for this: their kvmalloc() does not wait in the first place.
ifeq ($(VERSION).$(PATCHLEVEL),6.12)
obj-m += kvmalloc_nowait.o
endif
