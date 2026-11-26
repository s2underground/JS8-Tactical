/*
 * librtlsdr, opened from the file descriptor Android's UsbManager hands the app (an app can't
 * open /dev/bus/usb itself). libusb's libusb_wrap_sys_device() turns that descriptor into a
 * device handle; the few libusb calls librtlsdr's rtlsdr_open() makes to find and open a device
 * are redirected here to return that handle. librtlsdr itself is unmodified: it is compiled as
 * part of this file (vendor/rtl-sdr, osmocom v2.0.3, GPL-2.0-or-later), so rtlsdr_open() and its
 * tuner probing run exactly as upstream wrote them.
 *
 * Part of JS8-Tactical (GPL-3.0-or-later).
 */
#include <libusb.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>

#ifdef __ANDROID__
#include <android/log.h>
/* librtlsdr reports through fprintf(stderr); on Android that goes nowhere, so send it to logcat. */
#define fprintf(stream, ...) __android_log_print(ANDROID_LOG_INFO, "rtlsdr", __VA_ARGS__)
#endif

static pthread_mutex_t js8_open_lock = PTHREAD_MUTEX_INITIALIZER;
static intptr_t js8_open_fd = -1;
static libusb_device_handle *js8_open_devh = NULL;
static libusb_context *js8_open_ctx = NULL;

/* No device discovery: on Android the app can't enumerate /dev/bus/usb; it has one descriptor. */
static int js8_libusb_init(libusb_context **ctx)
{
	struct libusb_init_option opt[1];
	opt[0].option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY;
	opt[0].value.ival = 0;
	return libusb_init_context(ctx, opt, 1);
}

/* The "list" is the one device behind the descriptor being opened (or nothing). */
static ssize_t js8_get_device_list(libusb_context *ctx, libusb_device ***list)
{
	libusb_device **l = calloc(2, sizeof(*l));
	if (!l)
		return LIBUSB_ERROR_NO_MEM;
	if (js8_open_fd >= 0 && js8_open_devh == NULL) {
		if (libusb_wrap_sys_device(ctx, js8_open_fd, &js8_open_devh) == 0)
			js8_open_ctx = ctx;
		else
			js8_open_devh = NULL;
	}
	*list = l;
	if (js8_open_devh && js8_open_ctx == ctx) {
		l[0] = libusb_get_device(js8_open_devh);
		return 1;
	}
	return 0;
}

/* The device belongs to the wrapped handle: nothing to unreference. */
static void js8_free_device_list(libusb_device **list, int unref_devices)
{
	(void)unref_devices;
	free(list);
}

/* Opening the listed device hands over the already-open wrapped handle. */
static int js8_open(libusb_device *dev, libusb_device_handle **devh)
{
	if (!js8_open_devh || libusb_get_device(js8_open_devh) != dev)
		return LIBUSB_ERROR_NOT_FOUND;
	*devh = js8_open_devh;
	js8_open_devh = NULL; /* librtlsdr owns it now and closes it */
	return 0;
}

/* If rtlsdr_open() gives up before taking the handle, close it before its context goes. */
static void js8_exit(libusb_context *ctx)
{
	if (js8_open_devh && js8_open_ctx == ctx) {
		libusb_close(js8_open_devh);
		js8_open_devh = NULL;
	}
	libusb_exit(ctx);
}

#define libusb_init js8_libusb_init
#define libusb_get_device_list js8_get_device_list
#define libusb_free_device_list js8_free_device_list
#define libusb_open js8_open
#define libusb_exit js8_exit

#include "librtlsdr.c"

#undef libusb_init
#undef libusb_get_device_list
#undef libusb_free_device_list
#undef libusb_open
#undef libusb_exit

/* Opens the RTL-SDR behind an Android USB file descriptor. Returns 0 or a negative error. */
int rtlsdr_open_fd(rtlsdr_dev_t **out_dev, int fd)
{
	int r;
	if (fd < 0)
		return -1;
	pthread_mutex_lock(&js8_open_lock);
	js8_open_fd = fd;
	js8_open_devh = NULL;
	js8_open_ctx = NULL;
	r = rtlsdr_open(out_dev, 0);
	js8_open_fd = -1;
	js8_open_devh = NULL;
	js8_open_ctx = NULL;
	pthread_mutex_unlock(&js8_open_lock);
	return r;
}

/* RTL-SDR Blog V4 (and V4 Lite): an R828D/R820T with the built-in HF upconverter. */
int rtlsdr_js8_has_upconverter(rtlsdr_dev_t *dev)
{
	if (!dev)
		return 0;
	return rtlsdr_check_dongle_model(dev, "RTLSDRBlog", "Blog V4") ||
	       rtlsdr_check_dongle_model(dev, "RTLSDRBlog", "Blog V4L");
}
