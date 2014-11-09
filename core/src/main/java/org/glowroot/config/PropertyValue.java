/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.config;

import javax.annotation.Nullable;

import org.glowroot.config.PropertyDescriptor.PropertyType;

public class PropertyValue {

    // can be boolean, @Nullable Double or @NonNull String
    @Nullable
    private final Object value;

    static PropertyValue getDefaultValue(PropertyType type) {
        switch (type) {
            case BOOLEAN:
                return new PropertyValue(false);
            case DOUBLE:
                return new PropertyValue(null);
            case STRING:
                return new PropertyValue("");
            default:
                throw new AssertionError("Unexpected property type: " + type);
        }
    }

    public PropertyValue(@Nullable Object value) {
        this.value = value;
    }

    @Nullable
    public Object value() {
        return value;
    }
}