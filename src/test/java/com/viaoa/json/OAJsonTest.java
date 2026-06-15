package com.viaoa.json;

import org.junit.Test;

import com.corptostore.model.oa.CorpToStore;
import com.corptostore.model.oa.PurgeWindow;
import com.corptostore.model.pojo.CorpToStorePojoTest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.viaoa.datasource.objectcache.OADataSourceObjectCache;

public class OAJsonTest {

	@Test
	public void jsonTest() throws Exception {

		OADataSourceObjectCache ds = new OADataSourceObjectCache();
		ds.setAssignIdOnCreate(true);

		OAJson oj = new OAJson();
		oj.setIncludeAll(true);

		CorpToStorePojoTest pojoTest = new CorpToStorePojoTest();

		String json = pojoTest.jsonTest();
		System.out.println("1: =========\n" + json);

		CorpToStore cts = oj.readObject(json, CorpToStore.class, true);
		String json2 = oj.write(cts);
		System.out.println("2: =========\n" + json2);

		oj.readIntoObject(json, cts, true);
		json2 = oj.write(cts);
		System.out.println("3: =========\n" + json2);

		json = pojoTest.jsonTest(false);
		// cts = oj.readObject(json, CorpToStore.class, true);
		oj.readIntoObject(json, cts, true);

		json2 = oj.write(cts);
		System.out.println("4: =========\n" + json2);

		cts.getPurgeWindows().add(new PurgeWindow());

		oj.readIntoObject(json, cts, true);
		json2 = oj.write(cts);
		System.out.println("5: =========\n" + json2);

		int xx = 4;
		xx++;
	}

	public String fixMessageAdjustOverstockControl(String json) throws JsonProcessingException {
		OAJson oj = new OAJson();

		// ObjectMapper om = RpgJsonConverter.getObjectMapper();
		ObjectMapper om = oj.getObjectMapper();

		ObjectNode nodeRoot = (ObjectNode) om.readTree(json);
		JsonNode node = nodeRoot.get("subCode");
		if (node.isTextual()) {
			String value = node.asText();
			value = removeLeadingZeros(value);
			nodeRoot.put("subCode", value);
		}

		node = nodeRoot.get("plcd");
		if (node.isTextual()) {
			String value = node.asText();
			value = removeLeadingZeros(value);
			nodeRoot.put("plcd", value);
		}

		json = om.writeValueAsString(nodeRoot);
		return json;
	}

	public String removeLeadingZeros(String json) {
		for (;;) {
			if (json == null || json.length() < 2) {
				break;
			}
			if (json.charAt(0) != '0') {
				break;
			}
			json = json.substring(1);
		}
		return json;
	}

	public static void main2(String[] args) throws Exception {
		OAJsonTest oj = new OAJsonTest();

		for (;;) {
			String json = "";

			json = oj.fixMessageAdjustOverstockControl(json);

			int xx = 1;
			xx++;
		}
	}

	public static void main(String[] args) throws Exception {
		OAJsonTest test = new OAJsonTest();
		test.jsonTest();
	}

}

/* NOTES:

@JsonProperty("TaxableGroup")



*/

/**
* JSON serialization for OA. Works dynamically with OAObject Graphs to allow property paths to be included.
* <p>
* This is also able to work with POJO classes that dont always have pkey properties, but instead use importMatch properties or linkMany
* unique. OAJson will find (or create) the correct OAObject that does have the matching value(s). <br>
* ex: Customer.id, and Customer.custNumber, where OAObject Customer has an Id (pkey) and custNumber (int prop, but not key). The Pojo class
* does not have to have the Id.<br>
* also, a link could be an importMatch (ex: custNumber can be flagged as an importMatch).
* <p>
* Internally uses (/depends on) Jackson's ObjectMapper.
* <p>
* Note: this is not thread safe.
*
* @author vvia
*/



