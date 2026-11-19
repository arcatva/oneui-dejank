// SPDX-License-Identifier: GPL-2.0
/*
 * hold: keeps files in RAM.
 *
 *   hold <pid file> <file>...
 *
 * Maps each file and locks the mapping. The kernel then reads all of the file
 * in and does not drop any of it again, which is what Android's PinnerService
 * does with the files on its list.
 *
 * The work is done by a child that stays: once every file that can be held is
 * held, it writes its pid to the pid file, lets go of the terminal and sleeps
 * until it is killed; the files are let go with it. What it holds shows as
 * VmLck in /proc/<pid>/status. The command itself returns when the child has
 * got that far, with 0, or with 1 when no file could be held or the pid file
 * could not be written.
 */
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

static int hold(const char *path)
{
	struct stat st;
	void *p;
	int fd, err;

	fd = open(path, O_RDONLY | O_CLOEXEC);
	if (fd < 0) {
		fprintf(stderr, "hold: %s: %s\n", path, strerror(errno));
		return 0;
	}
	if (fstat(fd, &st) < 0) {
		err = errno;
		close(fd);
		fprintf(stderr, "hold: %s: %s\n", path, strerror(err));
		return 0;
	}
	if (!S_ISREG(st.st_mode) || st.st_size <= 0) {
		close(fd);
		fprintf(stderr, "hold: %s: not a file with something in it\n", path);
		return 0;
	}
	p = mmap(NULL, (size_t)st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
	err = errno;
	close(fd);
	if (p == MAP_FAILED) {
		fprintf(stderr, "hold: %s: mmap: %s\n", path, strerror(err));
		return 0;
	}
	if (mlock(p, (size_t)st.st_size) < 0) {
		err = errno;
		munmap(p, (size_t)st.st_size);
		fprintf(stderr, "hold: %s: mlock: %s\n", path, strerror(err));
		return 0;
	}
	return 1;
}

int main(int argc, char **argv)
{
	int ready[2];
	int i, fd, held = 0;
	char c = 0;
	pid_t pid;
	FILE *f;

	if (argc < 3) {
		fprintf(stderr, "usage: hold <pid file> <file>...\n");
		return 2;
	}
	if (pipe(ready) < 0) {
		fprintf(stderr, "hold: pipe: %s\n", strerror(errno));
		return 1;
	}
	pid = fork();
	if (pid < 0) {
		fprintf(stderr, "hold: fork: %s\n", strerror(errno));
		return 1;
	}
	if (pid > 0) {
		/* a byte means the child holds the files; none, that it gave up */
		close(ready[1]);
		return read(ready[0], &c, 1) == 1 ? 0 : 1;
	}

	close(ready[0]);
	setsid();
	signal(SIGHUP, SIG_IGN);
	signal(SIGPIPE, SIG_IGN);

	for (i = 2; i < argc; i++)
		held += hold(argv[i]);
	if (!held)
		return 1;

	f = fopen(argv[1], "w");
	if (!f || fprintf(f, "%d\n", (int)getpid()) < 0 || fclose(f)) {
		fprintf(stderr, "hold: %s: %s\n", argv[1], strerror(errno));
		return 1;
	}

	/* whoever reads what this command prints must not wait for the child */
	fd = open("/dev/null", O_RDWR);
	if (fd >= 0) {
		dup2(fd, 0);
		dup2(fd, 1);
		dup2(fd, 2);
		if (fd > 2)
			close(fd);
	}
	if (write(ready[1], "1", 1) != 1)
		return 1;
	close(ready[1]);

	for (;;)
		pause();
}
