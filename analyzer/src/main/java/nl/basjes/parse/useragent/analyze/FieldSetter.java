/*
 * Yet Another UserAgent Analyzer
 * Copyright (C) 2013-2017 Niels Basjes
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

package nl.basjes.parse.useragent.analyze;


import java.io.Serializable;
import java.util.Collection;

public interface FieldSetter {
    final class AgentField implements Serializable {
        public final String attribute;
        public final String value;
        public final long confidence;

        public AgentField(String attribute, String value, long confidence) {
            this.attribute = attribute;
            this.value = value;
            this.confidence = confidence;
        }

        @Override
        public String toString() {
            return ">" + this.value + "#" + this.confidence + "<";
        }

        public long nonNullConfidence() {
            return value == null ? -1L : confidence;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AgentField)) return false;

            AgentField that = (AgentField) o;

            if (confidence != that.confidence) return false;
            if (!attribute.equals(that.attribute)) return false;
            return value != null ? value.equals(that.value) : that.value == null;
        }

        @Override
        public int hashCode() {
            int result = attribute.hashCode();
            result = 31 * result + (value != null ? value.hashCode() : 0);
            result = 31 * result + (int) (confidence ^ (confidence >>> 32));
            return result;
        }
    }

    void set(Collection<AgentField> values);
}
