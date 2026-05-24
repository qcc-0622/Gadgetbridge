/*  Copyright (C) 2025 Freeyourgadget

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages;

import androidx.annotation.Nullable;

import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.FitRecordDataBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.RecordData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.RecordDefinition;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.RecordHeader;

/**
 * WARNING: This class was auto-generated, please avoid modifying it directly.
 * See {@link nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.codegen.FitCodeGen}
 *
 * @noinspection unused
 */
public class FitNap extends RecordData {
    public FitNap(final RecordDefinition recordDefinition, final RecordHeader recordHeader) {
        super(recordDefinition, recordHeader);

        final int nativeNumber = recordDefinition.getNativeFITMessage().getNumber();
        if (nativeNumber != 412) {
            throw new IllegalArgumentException("FitNap expects native messages of " + 412 + ", got " + nativeNumber);
        }
    }

    @Nullable
    public Long getStartTimestamp() {
        return getFieldByNumber(0, Long.class);
    }

    @Nullable
    public Integer getStartTzOffset() {
        return getFieldByNumber(1, Integer.class);
    }

    @Nullable
    public Long getEndTimestamp() {
        return getFieldByNumber(2, Long.class);
    }

    @Nullable
    public Integer getEndTzOffset() {
        return getFieldByNumber(3, Integer.class);
    }

    @Nullable
    public Integer getFeedback() {
        return getFieldByNumber(4, Integer.class);
    }

    @Nullable
    public Boolean getDeleted() {
        return getFieldByNumber(6, Boolean.class);
    }

    @Nullable
    public Long getUpdatedTimestamp() {
        return getFieldByNumber(7, Long.class);
    }

    @Nullable
    public Long getTimestamp() {
        return getFieldByNumber(253, Long.class);
    }

    @Nullable
    public Integer getMessageIndex() {
        return getFieldByNumber(254, Integer.class);
    }

    /**
     * @noinspection unused
     */
    public static class Builder extends FitRecordDataBuilder {
        public Builder() {
            super(412);
        }

        public Builder setStartTimestamp(final Long value) {
            setFieldByNumber(0, value);
            return this;
        }

        public Builder setStartTzOffset(final Integer value) {
            setFieldByNumber(1, value);
            return this;
        }

        public Builder setEndTimestamp(final Long value) {
            setFieldByNumber(2, value);
            return this;
        }

        public Builder setEndTzOffset(final Integer value) {
            setFieldByNumber(3, value);
            return this;
        }

        public Builder setFeedback(final Integer value) {
            setFieldByNumber(4, value);
            return this;
        }

        public Builder setDeleted(final Boolean value) {
            setFieldByNumber(6, value);
            return this;
        }

        public Builder setUpdatedTimestamp(final Long value) {
            setFieldByNumber(7, value);
            return this;
        }

        public Builder setTimestamp(final Long value) {
            setFieldByNumber(253, value);
            return this;
        }

        public Builder setMessageIndex(final Integer value) {
            setFieldByNumber(254, value);
            return this;
        }

        @Override
        public FitNap build() {
            return (FitNap) super.build();
        }

        @Override
        public FitNap build(final int localMessageType) {
            return (FitNap) super.build(localMessageType);
        }
    }
}
