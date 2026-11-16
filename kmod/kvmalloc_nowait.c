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
 * Linux 6.16 took the waiting out of the first attempt ("mm: kvmalloc: make
 * kmalloc fast path real fast path"). Android's 6.12 kernel has a vendor hook
 * at the top of the function instead, android_vh_kvmalloc_node_use_vmalloc,
 * with which a module sends a request straight to vmalloc(). This module
 * registers a probe on that hook and does so for requests larger than
 * PAGE_SIZE << PAGE_ALLOC_COSTLY_ORDER (32 KB with 4 KB pages): from there on
 * the first attempt needs a block of an order the kernel itself calls costly.
 * Smaller requests stay as they are.
 *
 * Only requests the kernel's own fallback would accept are sent there, so a
 * caller gets nothing it could not get before. No kernel code is changed;
 * unloading the module takes the probe off again.
 *
 *   insmod kvmalloc_nowait.ko
 *   /sys/module/kvmalloc_nowait/parameters/enabled  1: as above, 0: every request as the kernel has it
 *   /sys/module/kvmalloc_nowait/parameters/seen     requests above the limit since loading
 *   /sys/module/kvmalloc_nowait/parameters/sent     of those, sent straight to vmalloc()
 */
#define pr_fmt(fmt) "kvmalloc_nowait: " fmt

#include <linux/atomic.h>
#include <linux/gfp.h>
#include <linux/kernel.h>
#include <linux/limits.h>
#include <linux/mm.h>
#include <linux/mmzone.h>
#include <linux/module.h>
#include <linux/moduleparam.h>
#include <linux/swap.h>
#include <linux/tracepoint.h>
#include <trace/hooks/mm.h>

#define LIMIT	(PAGE_SIZE << PAGE_ALLOC_COSTLY_ORDER)

static unsigned int enabled = 1;
static atomic_long_t seen;
static atomic_long_t sent;

static void kv_probe(void *data, size_t size, gfp_t *kmalloc_flags, bool *use_vmalloc)
{
	gfp_t flags = *kmalloc_flags;

	if (size <= LIMIT)
		return;
	atomic_long_inc(&seen);
	if (!READ_ONCE(enabled))
		return;
	/* what the kernel's own fallback turns away stays with the kernel */
	if (!gfpflags_allow_blocking(flags) || size > INT_MAX)
		return;
	/* the caller asked for contiguous memory to be tried hard */
	if (flags & __GFP_RETRY_MAYFAIL)
		return;
	*use_vmalloc = true;
	atomic_long_inc(&sent);
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
MODULE_PARM_DESC(enabled, "1: large requests go straight to vmalloc(); 0: every request as the kernel has it");

static int count_get(char *buf, const struct kernel_param *kp)
{
	return scnprintf(buf, PAGE_SIZE, "%ld\n", atomic_long_read(kp->arg));
}

static const struct kernel_param_ops count_ops = {
	.get = count_get,
};
module_param_cb(seen, &count_ops, &seen, 0444);
MODULE_PARM_DESC(seen, "requests above the limit since loading");
module_param_cb(sent, &count_ops, &sent, 0444);
MODULE_PARM_DESC(sent, "requests sent straight to vmalloc()");

static int __init kvmalloc_nowait_init(void)
{
	int ret = register_trace_android_vh_kvmalloc_node_use_vmalloc(kv_probe, NULL);

	if (ret) {
		pr_err("the hook did not take the probe (%d): nothing done\n", ret);
		return ret;
	}
	pr_info("requests over %lu bytes go straight to vmalloc()\n", (unsigned long)LIMIT);
	return 0;
}

static void __exit kvmalloc_nowait_exit(void)
{
	unregister_trace_android_vh_kvmalloc_node_use_vmalloc(kv_probe, NULL);
	/* nobody may still be inside the probe when its code goes away */
	tracepoint_synchronize_unregister();
	pr_info("off: %ld of %ld large requests were sent to vmalloc()\n",
		atomic_long_read(&sent), atomic_long_read(&seen));
}

module_init(kvmalloc_nowait_init);
module_exit(kvmalloc_nowait_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Sends large kvmalloc() requests straight to vmalloc(), through Android's vendor hook");
