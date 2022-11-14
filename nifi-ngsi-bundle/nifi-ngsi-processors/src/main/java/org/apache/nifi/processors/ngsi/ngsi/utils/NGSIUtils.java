package org.apache.nifi.processors.ngsi.ngsi.utils;

import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.stream.io.StreamUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.*;

import org.apache.commons.collections.map.CaseInsensitiveMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class NGSIUtils {

    private static final Logger logger = LoggerFactory.getLogger(NGSIUtils.class);

    public static List<String> IGNORED_KEYS_ON_ATTRIBUTES =
            List.of("type", "value", "object", "datasetId", "createdAt", "modifiedAt", "instanceId", "observedAt");
    // FIXME even if createdAt and modifiedAt should not be present at entity level
    public static List<String> IGNORED_KEYS_ON_ENTITES = List.of("id", "type", "@context", "createdAt", "modifiedAt");

    public NGSIEvent getEventFromFlowFile(FlowFile flowFile, final ProcessSession session, String version) {

        final byte[] buffer = new byte[(int) flowFile.getSize()];

        session.read(flowFile, in -> StreamUtils.fillBuffer(in, buffer));
        // Create the PreparedStatement to use for this FlowFile.
        Map<String, String> flowFileAttributes = flowFile.getAttributes();
        Map<String, String> newFlowFileAttributes = new CaseInsensitiveMap(flowFileAttributes);
        final String flowFileContent = new String(buffer, StandardCharsets.UTF_8);
        String fiwareService = (newFlowFileAttributes.get("fiware-service") == null) ? "nd" : newFlowFileAttributes.get("fiware-service");
        String fiwareServicePath = (newFlowFileAttributes.get("fiware-servicepath") == null) ? "/nd" : newFlowFileAttributes.get("fiware-servicepath");
        System.out.println(fiwareServicePath);
        long creationTime = flowFile.getEntryDate();
        JSONArray data;
        String entityType;
        String entityId;
        ArrayList<Entity> entities = new ArrayList<>();
        NGSIEvent event = null;

        if ("v2".compareToIgnoreCase(version) == 0) {
            JSONObject content = new JSONObject(flowFileContent);
            data = (JSONArray) content.get("data");
            for (int i = 0; i < data.length(); i++) {
                JSONObject lData = data.getJSONObject(i);
                entityId = lData.getString("id");
                entityType = lData.getString("type");
                ArrayList<Attributes> attrs = new ArrayList<>();
                Iterator<String> keys = lData.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!"id".equals(key) && !"type".equals(key)) {
                        JSONObject value = lData.getJSONObject(key);
                        JSONObject mtdo = (JSONObject) value.get("metadata");
                        Iterator<String> keysOneLevel = mtdo.keys();
                        String metadataString = value.get("metadata").toString();
                        ArrayList<Metadata> mtd = new ArrayList<>();
                        while (keysOneLevel.hasNext()) {
                            String keyOne = keysOneLevel.next();
                            JSONObject value2 = mtdo.getJSONObject(keyOne);
                            mtd.add(new Metadata(keyOne, value2.getString("type"), value2.get("value").toString()));
                        }
                        if (mtdo.length() <= 0) {
                            attrs.add(new Attributes(key, value.getString("type"), value.get("value").toString(), null, ""));
                        } else {
                            attrs.add(new Attributes(key, value.getString("type"), value.get("value").toString(), mtd, metadataString));
                        }
                    }
                }
                entities.add(new Entity(entityId, entityType, attrs));
            }
            event = new NGSIEvent(creationTime, fiwareService, fiwareServicePath, entities);
        } else if ("ld".compareToIgnoreCase(version) == 0) {
            logger.debug("Received an NGSI-LD notification");

            JSONArray content = new JSONArray(flowFileContent);
            logger.debug("Received an NGSI-LD temporal data");
            entities = parseNgsiLdEntities(content);

            event = new NGSIEvent(creationTime, fiwareService, entities);
        }
        return event;
    }

    public ArrayList<Entity> parseNgsiLdEntities(JSONArray content) {
        ArrayList<Entity> entities = new ArrayList<>();
        String entityType;
        String entityId;
        for (int i = 0; i < content.length(); i++) {
            JSONObject temporalEntity = content.getJSONObject(i);
            entityId = temporalEntity.getString("id");
            entityType = temporalEntity.getString("type");
            logger.debug("Dealing with entity {} of type {}", entityId, entityType);
            ArrayList<AttributesLD> attributes = new ArrayList<>();
            Iterator<String> keys = temporalEntity.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!IGNORED_KEYS_ON_ENTITES.contains(key)) {
                    Object object = temporalEntity.get(key);
                    if (object instanceof JSONArray) {
                        // it is a multi-attribute (see section 4.5.5 in NGSI-LD specification)
                        JSONArray values = temporalEntity.getJSONArray(key);
                        for (int j = 0; j < values.length(); j++) {
                            JSONObject value = values.getJSONObject(j);
                            AttributesLD attributesLD = parseNgsiLdAttribute(key, value);
                            addAttributesIfCheked(attributes, attributesLD);
                        }
                    } else if (object instanceof JSONObject) {
                        AttributesLD attributesLD = parseNgsiLdAttribute(key, (JSONObject) object);
                        addAttributesIfCheked(attributes, attributesLD);
                    } else {
                        logger.warn("Attribute {} has unexpected value type: {}", key, object.getClass());
                    }
                }
            }

//            //here we group the observed and unobserved entities into one
//            String finalEntityId = entityId;
//            if(entities.stream().anyMatch(entity -> entity.entityId.equals(finalEntityId))){
//                for (int x=0;x<entities.size();x++) {
//                    if (entities.get(x).entityId.equals(finalEntityId)){
//                        ArrayList<AttributesLD> attributesLDS = entities.get(x).entityAttrsLD;
//                        attributesLDS.addAll(attributes);
//                        entities.get(x).setEntityAttrsLD(attributesLDS);
//                    }
//                }
//            } else entities.add(new Entity(entityId,entityType,attributes,true));

            entities.add(new Entity(entityId, entityType, attributes, true));
        }
        return entities;
    }

    private AttributesLD parseNgsiLdAttribute(String key, JSONObject value) {
        //When exporting the temporal history of an entity, the value of an attribute can be an empty array - as per the specification - if it has no history in the specified time range.
        // In this case, some flow file can give entity that contains attributes with only null values so attribute type can be set to null
        String attrType = value.optString("type");
        String datasetId = value.optString("datasetId");
        String observedAt = value.optString("observedAt");
        String createdAt = value.optString("createdAt");
        String modifiedAt = value.optString("modifiedAt");
        Object attrValue;
        ArrayList<AttributesLD> subAttributes = new ArrayList<>();

        if ("Relationship".contentEquals(attrType)) {
            attrValue = value.get("object").toString();
        } else if ("Property".contentEquals(attrType)) {
            attrValue = value.opt("value");
        } else if ("GeoProperty".contentEquals(attrType)) {
            attrValue = value;
        } else if("".contentEquals(attrType)){
            attrType = null;
            attrValue = null;
        } else {
            logger.warn("Unrecognized attribute type: {}", attrType);
            return null;
        }

        Iterator<String> keysOneLevel = value.keys();
        while (keysOneLevel.hasNext()) {
            String keyOne = keysOneLevel.next();
            if (("Property".equals(attrType) && "unitCode".equals(keyOne))) {
                if (value.get(keyOne) instanceof String)
                    subAttributes.add(new AttributesLD(keyOne.toLowerCase(), "Property", "", "", "", "", value.getString(keyOne), false, null));

            } else if ("RelationshipDetails".contains(keyOne)) {
                JSONObject relation = value.getJSONObject(keyOne);
                relation.remove("id");
                relation.remove("type");

                for (String relationKey : relation.keySet()) {
                    Object object = relation.get(relationKey);
                    if (object instanceof JSONArray) {
                        // it is a multi-attribute (see section 4.5.5 in NGSI-LD specification)
                        JSONArray valuesArray = relation.getJSONArray(relationKey);
                        for (int j = 0; j < valuesArray.length(); j++) {
                            JSONObject valueObject = valuesArray.getJSONObject(j);
                            AttributesLD subAttribute = parseNgsiLdSubAttribute(relationKey, valueObject);
                            addAttributesIfCheked(subAttributes, subAttribute);
                        }
                    } else if (object instanceof JSONObject) {
                        AttributesLD subAttribute = parseNgsiLdSubAttribute(relationKey, (JSONObject) object);
                        addAttributesIfCheked(subAttributes, subAttribute);
                    } else {
                        logger.warn("Sub Attribute {} has unexpected value type: {}", relationKey, object.getClass());
                    }
                }
            } else if (!IGNORED_KEYS_ON_ATTRIBUTES.contains(keyOne)) {
                Object object = value.get(keyOne);
                if (object instanceof JSONArray) {
                    JSONArray valuesArray = value.getJSONArray(keyOne);
                    for (int j = 0; j < valuesArray.length(); j++) {
                        JSONObject valueObject = valuesArray.getJSONObject(j);
                        AttributesLD subAttribute = parseNgsiLdSubAttribute(keyOne, valueObject);
                        addAttributesIfCheked(subAttributes, subAttribute);
                    }
                } else if (object instanceof JSONObject) {
                    AttributesLD subAttribute = parseNgsiLdSubAttribute(keyOne, value.getJSONObject(keyOne));
                    addAttributesIfCheked(subAttributes, subAttribute);
                } else {
                    logger.warn("Sub Attribute {} has unexpected value type: {}", keyOne, object.getClass());
                }
            }
        }

        return new AttributesLD(key.toLowerCase(), attrType, datasetId, observedAt, createdAt, modifiedAt, attrValue, !subAttributes.isEmpty(), subAttributes);
    }

    private AttributesLD parseNgsiLdSubAttribute(String key, JSONObject value) {
        String subAttrType = value.get("type").toString();
        Object subAttrValue = "";
        if ("Relationship".contentEquals(subAttrType)) {
            subAttrValue = value.get("object").toString();
        } else if ("Property".contentEquals(subAttrType)) {
            subAttrValue = value.get("value");
        } else if ("GeoProperty".contentEquals(subAttrType)) {
            subAttrValue = value.get("value").toString();
        }

        return new AttributesLD(key.toLowerCase(), subAttrType, "", "", "", "", subAttrValue, false, null);
    }

    //In NGSI-LD, we can't have a property with a null value or a null attribute
    // So we do not add the attribute if it contains a null value or attribute is null
    private void addAttributesIfCheked(ArrayList<AttributesLD> attributesLd, AttributesLD attributeLD) {
        if (attributeLD.getAttrValue() !=null && attributeLD.getAttrValue().toString() != "null")
            attributesLd.add(attributeLD);
    }
}
