// SPDX-License-Identifier: GPL-2.0
/*
 * kgsl_nowait
 *
 * The Adreno driver (msm_kgsl) asks the kernel for memory in blocks of several
 * sizes, largest first, and takes a smaller one when a large one is not to be
 * had. Its kgsl_gfp_mask() decides how hard the kernel may try:
 *
 *   order > 0:                               HIGHMEM | COMP | NORETRY | NOWARN
 *                                            (no reclaim: fail at once, step down)
 *   order >= kgsl_pool_retry_min_order > 0:  HIGHMEM | COMP | NOWARN |
 *                                            RETRY_MAYFAIL | DIRECT_RECLAIM | KSWAPD_RECLAIM
 *                                            (free memory on the spot and retry)
 *
 * kgsl_pool_retry_min_order is read once, at probe, from the device tree
 * ("qcom,mempool-retry-min-order"); where it is set, a thread that draws waits
 * tens of milliseconds inside the allocation for a block the driver would
 * have done without. This module writes 0 to that one number, which is the
 * driver's own "off", and writes the old value back when it is unloaded.
 * Nothing is hooked and no code is changed.
 *
 * The address of the number comes from /proc/kallsyms (the symbol is local to
 * the driver, so it cannot be linked against) and is checked by name before
 * anything is written.
 *
 *   insmod kgsl_nowait.ko addr=0x...
 *   /sys/module/kgsl_nowait/parameters/saved    what the driver had
 *   /sys/module/kgsl_nowait/parameters/now      what it has now
 *   /sys/module/kgsl_nowait/parameters/enabled  1: keep it at 0, 0: the driver's own value
 */
#define pr_fmt(fmt) "kgsl_nowait: " fmt

#include <linux/kernel.h>
#include <linux/module.h>
#include <linux/moduleparam.h>
#include <linux/string.h>

#define VAR_NAME	"kgsl_pool_retry_min_order"
#define VAR_OWNER	" [msm_kgsl]"
#define ORDER_MAX	10

static unsigned long long addr;	/* where the driver keeps the number, as given at load */
static int *var;		/* the same, once it has been checked */
static unsigned int saved;	/* what the driver had */
static unsigned int enabled = 1;

static bool starts_with(const char *s, const char *prefix)
{
	while (*prefix) {
		if (*s++ != *prefix++)
			return false;
	}
	return true;
}

static bool ends_with(const char *s, const char *suffix)
{
	size_t a = strlen(s), b = strlen(suffix);

	return b <= a && starts_with(s + a - b, suffix);
}

static int addr_set(const char *val, const struct kernel_param *kp)
{
	if (var)
		return -EBUSY;
	return kstrtoull(val, 0, &addr);
}

static int addr_get(char *buf, const struct kernel_param *kp)
{
	return scnprintf(buf, PAGE_SIZE, "0x%llx\n", addr);
}

static const struct kernel_param_ops addr_ops = {
	.set = addr_set,
	.get = addr_get,
};
module_param_cb(addr, &addr_ops, NULL, 0400);
MODULE_PARM_DESC(addr, "address of kgsl_pool_retry_min_order, from /proc/kallsyms");

static int enabled_set(const char *val, const struct kernel_param *kp)
{
	unsigned int v;
	int ret = kstrtouint(val, 0, &v);

	if (ret)
		return ret;
	if (v > 1)
		return -EINVAL;
	enabled = v;
	if (var)
		WRITE_ONCE(*var, v ? 0 : (int)saved);
	return 0;
}

static int enabled_get(char *buf, const struct kernel_param *kp)
{
	return scnprintf(buf, PAGE_SIZE, "%u\n", enabled);
}

static const struct kernel_param_ops enabled_ops = {
	.set = enabled_set,
	.get = enabled_get,
};
module_param_cb(enabled, &enabled_ops, NULL, 0644);
MODULE_PARM_DESC(enabled, "1: large blocks never wait (the number is 0); 0: the driver's own value");

static int saved_get(char *buf, const struct kernel_param *kp)
{
	return scnprintf(buf, PAGE_SIZE, "%u\n", saved);
}

static const struct kernel_param_ops saved_ops = {
	.get = saved_get,
};
module_param_cb(saved, &saved_ops, NULL, 0444);
MODULE_PARM_DESC(saved, "the value the driver had when this module was loaded");

static int now_get(char *buf, const struct kernel_param *kp)
{
	return scnprintf(buf, PAGE_SIZE, "%d\n", var ? READ_ONCE(*var) : -1);
}

static const struct kernel_param_ops now_ops = {
	.get = now_get,
};
module_param_cb(now, &now_ops, NULL, 0444);
MODULE_PARM_DESC(now, "the value the driver has at this moment");

static int __init kgsl_nowait_init(void)
{
	char name[128];
	int *p = (int *)(unsigned long)addr;
	int v;

	if (!addr || (addr & 3)) {
		pr_err("no address given (addr=0x...), nothing done\n");
		return -EINVAL;
	}

	/* "kgsl_pool_retry_min_order+0x0/0x... [msm_kgsl]" and nothing else will do */
	scnprintf(name, sizeof(name), "%pS", p);
	if (!starts_with(name, VAR_NAME "+0x0/") || !ends_with(name, VAR_OWNER)) {
		pr_err("0x%llx is '%s', not " VAR_NAME ": nothing done\n", addr, name);
		return -EINVAL;
	}

	v = READ_ONCE(*p);
	if (v < 0 || v > ORDER_MAX) {
		pr_err(VAR_NAME " holds %d, which is no block order: nothing done\n", v);
		return -ERANGE;
	}

	saved = v;
	var = p;
	if (enabled)
		WRITE_ONCE(*p, 0);
	pr_info(VAR_NAME " was %d, is now %d\n", v, READ_ONCE(*p));
	return 0;
}

static void __exit kgsl_nowait_exit(void)
{
	if (var) {
		WRITE_ONCE(*var, (int)saved);
		pr_info(VAR_NAME " is back at %u\n", saved);
	}
}

module_init(kgsl_nowait_init);
module_exit(kgsl_nowait_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Turns off the Adreno driver's wait-and-retry for large blocks");
