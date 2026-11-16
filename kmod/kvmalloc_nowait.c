// SPDX-License-Identifier: GPL-2.0
/*
 * kvmalloc_nowait
 *
 * kvmalloc() first asks for one block of contiguous memory and falls back to
 * vmalloc(), which takes single pages, when there is none. Up to Linux 6.15
 * the first attempt may free memory on the spot to get its block. On a phone
 * whose memory is fragmented that takes milliseconds, and the caller can be a
 * thread that draws: the Adreno driver keeps the list of a buffer's pages in
 * such a block, 36 KB for a full screen.
 *
 * Linux 6.16 took the waiting out ("mm: kvmalloc: make kmalloc fast path real
 * fast path"). Where kmalloc_gfp_adjust() in 6.12 has
 *
 *	if (size > PAGE_SIZE) {
 *		...
 *		if (!(flags & __GFP_RETRY_MAYFAIL))
 *			flags |= __GFP_NORETRY;
 *
 * it now has
 *
 *			flags &= ~__GFP_DIRECT_RECLAIM;
 *
 * Android's 6.12 kernel calls a vendor hook at the end of that function,
 * android_vh_adjust_kvmalloc_flags, with the order of the request and the
 * flags of the first attempt. The probe registered here makes the change of
 * 6.16 there, under the same two conditions and nothing else: an order above
 * 0 is a size above PAGE_SIZE. __GFP_NORETRY, which 6.12 has set by then,
 * says how hard to reclaim and means nothing to an attempt that does not.
 * The flags of the fallback are the caller's own and are not seen here.
 *
 * No kernel code is changed; unloading the module takes the probe off again.
 *
 *   insmod kvmalloc_nowait.ko
 *   /sys/module/kvmalloc_nowait/parameters/enabled  1: as above, 0: every request as the kernel has it
 *   /sys/module/kvmalloc_nowait/parameters/seen     requests above PAGE_SIZE since loading
 *   /sys/module/kvmalloc_nowait/parameters/changed  of those, first attempts that lost the wait
 */
#define pr_fmt(fmt) "kvmalloc_nowait: " fmt

#include <linux/atomic.h>
#include <linux/gfp.h>
#include <linux/kernel.h>
#include <linux/mm.h>
#include <linux/mmzone.h>
#include <linux/module.h>
#include <linux/moduleparam.h>
#include <linux/swap.h>
#include <linux/tracepoint.h>
#include <trace/hooks/mm.h>

static unsigned int enabled = 1;
static atomic_long_t seen;
static atomic_long_t changed;

static void kv_probe(void *data, unsigned int order, gfp_t *alloc_flags)
{
	gfp_t flags = *alloc_flags;

	if (!order)
		return;
	atomic_long_inc(&seen);
	if (!READ_ONCE(enabled))
		return;
	if (flags & __GFP_RETRY_MAYFAIL)
		return;
	if (!(flags & __GFP_DIRECT_RECLAIM))
		return;
	*alloc_flags = flags & ~__GFP_DIRECT_RECLAIM;
	atomic_long_inc(&changed);
}

static int enabled_set(const char *val, const struct kernel_param *kp)
{
	unsigned int v;
	int ret = kstrtouint(val, 0, &v);

	if (ret)
		return ret;
	if (v > 1)
		return -EINVAL;
	WRITE_ONCE(enabled, v);
	return 0;
}

static int enabled_get(char *buf, const struct kernel_param *kp)
{
	return scnprintf(buf, PAGE_SIZE, "%u\n", READ_ONCE(enabled));
}

static const struct kernel_param_ops enabled_ops = {
	.set = enabled_set,
	.get = enabled_get,
};
module_param_cb(enabled, &enabled_ops, NULL, 0644);
MODULE_PARM_DESC(enabled, "1: the first attempt of kvmalloc() does not reclaim; 0: every request as the kernel has it");

static int count_get(char *buf, const struct kernel_param *kp)
{
	return scnprintf(buf, PAGE_SIZE, "%ld\n", atomic_long_read(kp->arg));
}

static const struct kernel_param_ops count_ops = {
	.get = count_get,
};
module_param_cb(seen, &count_ops, &seen, 0444);
MODULE_PARM_DESC(seen, "requests above PAGE_SIZE since loading");
module_param_cb(changed, &count_ops, &changed, 0444);
MODULE_PARM_DESC(changed, "first attempts that lost the wait");

static int __init kvmalloc_nowait_init(void)
{
	int ret = register_trace_android_vh_adjust_kvmalloc_flags(kv_probe, NULL);

	if (ret) {
		pr_err("the hook did not take the probe (%d): nothing done\n", ret);
		return ret;
	}
	pr_info("the first attempt of kvmalloc() no longer reclaims, as from Linux 6.16\n");
	return 0;
}

static void __exit kvmalloc_nowait_exit(void)
{
	unregister_trace_android_vh_adjust_kvmalloc_flags(kv_probe, NULL);
	/* nobody may still be inside the probe when its code goes away */
	tracepoint_synchronize_unregister();
	pr_info("off: %ld of %ld first attempts were changed\n",
		atomic_long_read(&changed), atomic_long_read(&seen));
}

module_init(kvmalloc_nowait_init);
module_exit(kvmalloc_nowait_exit);

MODULE_LICENSE("GPL");
MODULE_VERSION("2");
MODULE_DESCRIPTION("kvmalloc() without the wait in its first attempt, as from Linux 6.16, through Android's vendor hook");
