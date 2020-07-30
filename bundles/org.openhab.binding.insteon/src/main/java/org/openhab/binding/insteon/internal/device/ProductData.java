/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.insteon.internal.device;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.insteon.internal.utils.ByteUtils;

/**
 * Class that represents device product data
 *
 * @author Jeremy Setton - Initial contribution
 */
@NonNullByDefault
public class ProductData {
    private @Nullable String deviceCategory = null;
    private @Nullable String subCategory = null;
    private @Nullable String productKey = null;
    private @Nullable String description = null;
    private @Nullable String model = null;
    private @Nullable DeviceType deviceType = null;
    private int firmware = 0;
    private int hardware = 0;

    public @Nullable String getDeviceCategory() {
        return deviceCategory;
    }

    public @Nullable String getSubCategory() {
        return subCategory;
    }

    public @Nullable String getProductKey() {
        return productKey;
    }

    public @Nullable String getDescription() {
        return description;
    }

    public @Nullable String getModel() {
        return model;
    }

    public @Nullable DeviceType getDeviceType() {
        return deviceType;
    }

    public int getFirmwareVersion() {
        return firmware;
    }

    public int getHardwareVersion() {
        return hardware;
    }

    public @Nullable String getFullDescription() {
        return description != null ? model != null ? description + " " + model : description : null;
    }

    public boolean isSameProductID(ProductData productData) {
        return Objects.equals(deviceCategory, productData.getDeviceCategory())
                && Objects.equals(subCategory, productData.getSubCategory());
    }

    public boolean isSameDeviceType(ProductData productData) {
        return Objects.equals(deviceType, productData.getDeviceType());
    }

    public void setDeviceCategory(@Nullable String deviceCategory) {
        this.deviceCategory = deviceCategory;
    }

    public void setSubCategory(@Nullable String subCategory) {
        this.subCategory = subCategory;
    }

    public void setProductKey(@Nullable String productKey) {
        this.productKey = productKey;
    }

    public void setDescription(@Nullable String description) {
        this.description = description;
    }

    public void setModel(@Nullable String model) {
        this.model = model;
    }

    public void setDeviceType(@Nullable DeviceType deviceType) {
        this.deviceType = deviceType;
    }

    public void setFirmwareVersion(int firmware) {
        this.firmware = firmware;
    }

    public void setHardwareVersion(int hardware) {
        this.hardware = hardware;
    }

    public void update(ProductData productData) {
        // update device and sub category if not defined already
        if (deviceCategory == null && subCategory == null) {
            deviceCategory = productData.getDeviceCategory();
            subCategory = productData.getSubCategory();
        }
        // update device type if not defined already
        if (deviceType == null) {
            deviceType = productData.getDeviceType();
        }
        // update remaining properties if defined in given product data
        if (productData.getProductKey() != null) {
            productKey = productData.getProductKey();
        }
        if (productData.getDescription() != null) {
            description = productData.getDescription();
        }
        if (productData.getModel() != null) {
            model = productData.getModel();
        }
        if (productData.getFirmwareVersion() > 0) {
            firmware = productData.getFirmwareVersion();
        }
        if (productData.getHardwareVersion() > 0) {
            hardware = productData.getHardwareVersion();
        }
    }

    @Override
    public ProductData clone() {
        ProductData productData = new ProductData();
        productData.setDeviceCategory(deviceCategory);
        productData.setSubCategory(subCategory);
        productData.setProductKey(productKey);
        productData.setDescription(description);
        productData.setModel(model);
        productData.setDeviceType(deviceType);
        productData.setFirmwareVersion(firmware);
        productData.setHardwareVersion(hardware);
        return productData;
    }

    @Override
    @SuppressWarnings("null")
    public String toString() {
        List<String> properties = new ArrayList<>();
        if (deviceCategory != null) {
            properties.add("deviceCategory:" + deviceCategory);
        }
        if (subCategory != null) {
            properties.add("subCategory:" + subCategory);
        }
        if (productKey != null) {
            properties.add("productKey:" + productKey);
        }
        if (description != null) {
            properties.add("description:" + description);
        }
        if (model != null) {
            properties.add("model:" + model);
        }
        if (deviceType != null) {
            properties.add("deviceType:" + deviceType.getName());
        }
        if (firmware != 0) {
            properties.add("firmwareVersion:" + ByteUtils.getHexString(firmware));
        }
        if (hardware != 0) {
            properties.add("hardwareVersion:" + ByteUtils.getHexString(hardware));
        }

        return properties.isEmpty() ? "undefined product data" : String.join("|", properties);
    }

    /**
     * Factory method for getting a ProductData for Insteon product
     *
     * @param  deviceCategory the Insteon device category
     * @param  subCategory    the Insteon sub category
     * @param  productKey     the Insteon product key
     * @return                the product data
     */
    public static ProductData makeInsteonProduct(@Nullable String deviceCategory, @Nullable String subCategory,
              @Nullable String productKey) {
        ProductData productData = new ProductData();
        productData.setDeviceCategory(deviceCategory);
        productData.setSubCategory(subCategory);
        productData.setProductKey(productKey);
        return productData;
    }

    /**
     * Factory method for getting a ProductData for X10 product
     *
     * @param  deviceType the X10 device type
     * @return            the product data
     */
    public static ProductData makeX10Product(DeviceType deviceType) {
        ProductData productData = new ProductData();
        productData.setDeviceType(deviceType);
        productData.setDescription(deviceType.getName().replaceAll("_", " "));
        return productData;
    }
}
