package edu.emory.cci.aiw.i2b2etl.table;

/*
 * #%L
 * AIW i2b2 ETL
 * %%
 * Copyright (C) 2012 - 2015 Emory University
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import edu.emory.cci.aiw.i2b2etl.configuration.DataSection;
import edu.emory.cci.aiw.i2b2etl.configuration.DictionarySection;
import edu.emory.cci.aiw.i2b2etl.metadata.MetadataUtil;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.protempa.proposition.Proposition;
import org.protempa.proposition.UniqueId;
import org.protempa.proposition.value.Value;

/**
 *
 * @author arpost
 */
public class DimensionFactory {
    private final DictionarySection dictSection;
    private final DataSection obxSection;

    public DimensionFactory(DictionarySection dictSection,
            DataSection obxSection) {
        this.dictSection = dictSection;
        this.obxSection = obxSection;
    }

    protected DictionarySection getDictSection() {
        return dictSection;
    }

    protected DataSection getObxSection() {
        return obxSection;
    }
    
    protected Value getField(String field, Proposition encounterProp, Map<UniqueId, Proposition> references) {
        Value val;
        String obxSectionStr = dictSection.get(field);
        if (obxSectionStr != null) {
            DataSection.DataSpec obxSpec = obxSection.get(obxSectionStr);
            assert obxSpec.propertyName != null : "propertyName cannot be null";
            if (obxSpec != null) {
                if (obxSpec.referenceName != null) {
                    List<UniqueId> uids = encounterProp.getReferences(obxSpec.referenceName);
                    int size = uids.size();
                    if (size > 0) {
                        if (size > 1) {
                            Logger logger = TableUtil.logger();
                            logger.log(Level.WARNING,
                                    "Multiple propositions with {0} property found for {1}, using only the first one",
                                    new Object[]{field, encounterProp});
                        }
                        Proposition prop = references.get(uids.get(0));
                        val = prop.getProperty(obxSpec.propertyName);
                    } else {
                        val = null;
                    }
                } else {
                    val = encounterProp.getProperty(obxSpec.propertyName);
                }
            } else {
                throw new AssertionError("Invalid key referred to in " + field + ": " + obxSectionStr);
            }
        } else {
            val = null;
        }
        return val;
    }
}
