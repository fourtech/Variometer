#include <linux/module.h>
#include <linux/init.h>
#include <linux/slab.h>
#include <linux/i2c.h>
#include <linux/mutex.h>
#include <linux/delay.h>
#include <linux/interrupt.h>
#include <linux/irq.h>
#include <linux/hwmon-sysfs.h>
#include <linux/err.h>
#include <linux/hwmon.h>
#include <linux/input-polldev.h>
#include <linux/device.h>

#define I2C_MAJOR        125
#define MODULE_NAME      "ms5611"
#define	MS5611_DEV_NAME  "altimeter"
#define	MS5611_CLS_NAME  "altimeter_dev"

// registers of the device
#define MS561101BA_D1     0x40
#define MS561101BA_D2     0x50
#define MS561101BA_RESET  0x1E

// OSR (Over Sampling Ratio) constants
#define MS561101BA_OSR_256   0x00
#define MS561101BA_OSR_512   0x02
#define MS561101BA_OSR_1024  0x04
#define MS561101BA_OSR_2048  0x06
#define MS561101BA_OSR_4096  0x08

#define MS561101BA_PROM_BASE_ADDR  0xA0
#define MS561101BA_PROM_REG_COUNT  8 // number of registers in the PROM
#define MS561101BA_PROM_REG_SIZE   2 // size in bytes of a prom registry.

#define MS561101BA_DEFAULT_OSR     MS561101BA_OSR_4096 // set default OSR
#define MS561101BA_READ_CONV_DELAY 18                  // read converter delay time

#define IOCTL_GET_PRES_TEMP  _IOR('A', 0x01, struct altimeter_t)
#define IOCTL_GET_PRES       _IOR('A', 0x02, int32_t)
#define IOCTL_GET_TEMP       _IOR('A', 0x03, int32_t)

struct altimeter_t {
	int32_t pressure;
	int32_t temperature;
};

static struct class *i2c_dev_class;
static struct i2c_client *this_client;
static struct mutex ms5611_mutex;

static uint16_t ms5611_c[MS561101BA_PROM_REG_COUNT];

static int ms5611_i2c_write(struct i2c_client *client, uint8_t *buf, uint16_t len) {
	// obtain a message
	struct i2c_msg msg[] = {
			{
					.addr = client->addr,
					.flags = 0,
					.len = len,
					.buf = buf,
			}
	};
	// begin transfer
	if (i2c_transfer(client->adapter, msg, 1) < 0) {
		printk("ms5611: write(), transfer error\n");
		return -EIO;
	} else {
		printk("ms5611: write() success.\n");
		return 1;
	}
}

static int ms5611_i2c_read(struct i2c_client *client, uint8_t *buf, uint16_t len) {
	// obtain a message
	struct i2c_msg msgs[] = {
			{
					.addr = client->addr,
					.flags = 0 | I2C_M_NOSTART,
					.len = 1,
					.buf = &buf[0],
			},
			{
					.addr = client->addr,
					.flags = I2C_M_RD,
					.len = len,
					.buf = &buf[1],
			}
	};
	// begin transfer
	if (i2c_transfer(client->adapter, msgs, 2) < 0) {
		printk("ms5611: read(), transfer error\n");
		return -EIO;
	} else {
		printk("ms5611: read() success.\n");
		return 1;
	}

	return 1;
}

static void MS5611_Bus_Write(unsigned char reg_addr, unsigned char reg_data) {
	int ret = 0;
	int retry = 0;
	unsigned char data[3] = { reg_addr, reg_data, 0 };

	for (retry = 0; retry < 3; retry++) {
		ret = ms5611_i2c_write(this_client, data, 2);
		if (ret == 1) break;
		msleep(5);
	}
}

static int32_t MS5611_Bus_Read(unsigned char reg_addr) {
	int ret = 0;
	int retry = 0;
	unsigned char wdata[3] = { reg_addr, 0 };
	unsigned char rdata[5] = { 0, 0, 0, 0, 0 };

	for (retry = 0; retry < 3; retry++) {
		if (ms5611_i2c_write(this_client, wdata, 1) == 1) {
			msleep(MS561101BA_READ_CONV_DELAY);
			ret = ms5611_i2c_read(this_client, rdata, 3);
			if (ret == 1) break;
		}
		msleep(5);
	}

	return (ret ? rdata[1] << 16 | rdata[2] << 8 | rdata[3] : 0);
}

static int32_t MS5611_Bus_Read2(unsigned char reg_addr) {
	int ret = 0;
	int retry = 0;
	unsigned char data[3] = { reg_addr, 0, 0, 0 };

	for (retry = 0; retry < 3; retry++) {
		ret = ms5611_i2c_read(this_client, data, 2);
		if (ret == 1) break;
		msleep(5);
	}

	return (ret ? data[1] << 8 | data[2] : 0);
}

static int32_t MS5611_Bus_Read_PROM(void) {
	int i = 0;
	for (i = 0; i < MS561101BA_PROM_REG_COUNT; i++) {
		ms5611_c[i] = MS5611_Bus_Read2(MS561101BA_PROM_BASE_ADDR + (i * MS561101BA_PROM_REG_SIZE));
		printk("MS5611: MS5611_Bus_Read_PROM() ms5611_c[%d]=%02X \n", i, ms5611_c);
	}
	return 0;
}

static int8_t MS5611_Correct_PROM(void) {
	int i, j;
	uint32_t res = 0;
	uint8_t zero = 1, crc;
	uint16_t *prom = ms5611_c;

	crc = prom[7] & 0xF;
	prom[7] &= 0xFF00;

	for (i = 0; i < 8; i++) {
		if (prom[i] != 0) zero = 0;
	}

	if (zero) {
		printk("MS5611: MS5611_PROM_Correct() proms non zero.\n");
		return -1;
	}

	for (i = 0; i < 16; i++) {
		if (i & 1) {
			res ^= ((prom[i >> 1]) & 0x00FF);
		} else {
			res ^= (prom[i >> 1] >> 8);
		}

		for (j = 8; j > 0; j--) {
			if (res & 0x8000) {
				res ^= 0x1800;
			}
			res <<= 1;
		}
	}

	prom[7] |= crc;

	return (crc == ((res >> 12) & 0xF)) ? 0 : -1;
}

static int32_t MS5611_Get_Pressure_ADC(void) {
	return MS5611_Bus_Read(MS561101BA_D1 + MS561101BA_DEFAULT_OSR);
}

static int32_t MS5611_Get_Temperature_ADC(void) {
	return MS5611_Bus_Read(MS561101BA_D2 + MS561101BA_DEFAULT_OSR);
}

void MS5611_Calculate(int32_t *pressure, int32_t *temperature) {
	uint32_t press;
	int64_t temp;
	int64_t delt;
	uint32_t ms5611_ut;
	uint32_t ms5611_up;
	ms5611_ut = MS5611_Get_Temperature_ADC();
	ms5611_up = MS5611_Get_Pressure_ADC();

	int32_t dT = (int64_t) ms5611_ut - ((uint64_t) ms5611_c[5] * 256);
	int64_t off = ((int64_t) ms5611_c[2] << 16) + (((int64_t) ms5611_c[4] * dT) >> 7);
	int64_t sens = ((int64_t) ms5611_c[1] << 15) + (((int64_t) ms5611_c[3] * dT) >> 8);
	temp = 2000 + ((dT * (int64_t) ms5611_c[6]) >> 23);

	if (temp < 2000) { // temperature lower than 20degC
		delt = temp - 2000;
		delt = 5 * delt * delt;
		off -= delt >> 1;
		sens -= delt >> 2;

		if (temp < -1500) { // temperature lower than -15degC
			delt = temp + 1500;
			delt = delt * delt;
			off -= 7 * delt;
			sens -= (11 * delt) >> 1;
		}
	}

	press = ((((int64_t) ms5611_up * sens) >> 21) - off) >> 15;

	if (pressure) *pressure = press;
	if (temperature) *temperature = temp;
}

static int ms5611_open(struct inode *inode, struct file *file) {
	printk("MS5611: ms5611_open()\n");
	return 0;
}

static int ms5611_close(struct inode *inode, struct file *file) {
	printk("MS5611: ms5611_close()\n");
	return 0;
}

static long ms5611_ioctl(struct file *file, unsigned int cmd, unsigned long arg) {
	mutex_lock(&ms5611_mutex);

	void __user *argp = (void __user *)arg;

	switch (cmd) {
	case IOCTL_GET_PRES_TEMP: {
		struct altimeter_t at;
		MS5611_Calculate(&at.pressure, &at.temperature);
		printk("MS5611: ms5611_ioctl {pressure=%d, temperature=%d}\n", at.pressure, at.temperature);
		if (copy_to_user(argp, &at, sizeof(struct altimeter_t))) {
			printk("UVC_REVERSE: IOCTL_GET_PRES_TEMP error\n");
			return -EFAULT;
		}
		break;
	}
	case IOCTL_GET_PRES: {
		struct altimeter_t at;
		MS5611_Calculate(&at.pressure, &at.temperature);
		printk("MS5611: ms5611_ioctl pressure=%d\n", at.pressure);
		if (copy_to_user(argp, &at.pressure, sizeof(int32_t))) {
			printk("UVC_REVERSE: IOCTL_GET_PRES error\n");
			return -EFAULT;
		}
		break;
	}
	case IOCTL_GET_TEMP: {
		struct altimeter_t at;
		MS5611_Calculate(&at.pressure, &at.temperature);
		printk("MS5611: ms5611_ioctl temperature=%d\n", at.temperature);
		if (copy_to_user(argp, &at.temperature, sizeof(int32_t))) {
			printk("UVC_REVERSE: IOCTL_GET_TEMP error\n");
			return -EFAULT;
		}
		break;
	}
	default:
		printk("MS5611: ms5611_ioctl() cmd=UNKNOWN, arg=%ld\n", arg);
		break;
	}

	mutex_unlock(&ms5611_mutex);
	return 1;
}

static const struct file_operations ms5611_fops = {
		.owner = THIS_MODULE,
		//.read = ms5611_read,
		//.write = ms5611_write,
		.open = ms5611_open,
		.release = ms5611_close,
		.unlocked_ioctl = ms5611_ioctl,
};

static int ms5611_probe(struct i2c_client *client,
		const struct i2c_device_id *id) {
	int ret = -1;
	struct device *dev;
	this_client = client;
	printk("ms5611: ms5611_probe()\n");

	ret = register_chrdev(I2C_MAJOR, MS5611_DEV_NAME, &ms5611_fops);
	if (ret) {
		printk("ms5611: ms5611_probe() register_chrdev failed\n");
		return ret;
	}

	i2c_dev_class = class_create(THIS_MODULE, MS5611_CLS_NAME);
	if (IS_ERR(i2c_dev_class)) {
		ret = PTR_ERR(i2c_dev_class);
		class_destroy(i2c_dev_class);
		printk("ms5611: ms5611_probe() class_create failed\n");
	}

	msleep(100);
	MS5611_Bus_Read2(MS561101BA_PROM_BASE_ADDR);

	msleep(100);
	MS5611_Bus_Write(MS561101BA_RESET, 0);

	msleep(100);
	MS5611_Bus_Read_PROM();
	if (MS5611_Correct_PROM() != 0) {
		printk("ms5611: ms5611_probe() correct PROM failed.\n");
	}

	mutex_init(&ms5611_mutex);

	dev = device_create(i2c_dev_class, &client->adapter->dev,
			MKDEV(I2C_MAJOR, client->adapter->nr), NULL, MS5611_DEV_NAME);

	if (IS_ERR(dev)) {
		ret = PTR_ERR(dev);
		printk("ms5611: ms5611_probe() device_create failed\n");
		return ret;
	}

	return 0;
}

static int ms5611_suspend(struct i2c_client *client, pm_message_t mesg) {
	printk("MS5611: ms5611_suspend()\n");
	return 0;
}

static int ms5611_resume(struct i2c_client *client) {
	printk("MS5611: ms5611_resume()\n");
	return 0;
}

static struct of_device_id ms5611_dt_ids[] = {
		{ .compatible = MODULE_NAME },
		{ }
};

static const struct i2c_device_id ms5611_id[] = {
		{ "ms5611-01ba01", 0 },
		{ }
};

MODULE_DEVICE_TABLE( i2c, ms5611_id);

static struct i2c_driver ms5611_driver = {
		.class = I2C_CLASS_HWMON,
		.probe = ms5611_probe,
		//.remove = __devexit_p(ms5611_remove),
		.suspend = ms5611_suspend,
		.resume = ms5611_resume,
		.id_table = ms5611_id,
		.driver = {
				.name = MODULE_NAME,
				.owner = THIS_MODULE,
				.of_match_table = of_match_ptr(ms5611_dt_ids),
		},
		//.address_list	= u_i2c_addr.normal_i2c,
};

static int __init ms5611_init(void) {
	int ret = -1;
	printk("ms5611: ms5611_init()...\n");
	ret = i2c_add_driver(&ms5611_driver);
	return ret;
}

static void __exit ms5611_exit(void) {
	printk("ms5611: ms5611_exit()...\n");
	unregister_chrdev(I2C_MAJOR, MS5611_DEV_NAME);
	class_destroy(i2c_dev_class);
	i2c_del_driver(&ms5611_driver);
}

late_initcall(ms5611_init);
module_exit(ms5611_exit);

MODULE_AUTHOR("Fourtech Technology Co.,Ltd");
MODULE_DESCRIPTION("MS5611-01BA Variometer Module");
MODULE_LICENSE("GPL");
