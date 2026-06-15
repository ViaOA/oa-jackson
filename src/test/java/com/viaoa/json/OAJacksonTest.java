package com.viaoa.json;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.messagedesigner.model.oa.MessageSource;
import com.messagedesigner.model.oa.MessageType;
import com.messagedesigner.model.oa.MessageTypeRecord;
import com.messagedesigner.model.oa.propertypath.MessageSourcePP;
import com.viaoa.OAUnitTest;
import com.viaoa.datetime.OADateTime;
import com.viaoa.json.OAJson;
import com.viaoa.object.OAObjectCacheDelegate;

public class OAJacksonTest extends OAUnitTest {

	@Test
	public void configTest() {
		OAJson oaj = new OAJson();

		assertTrue(oaj.getIncludeOwned());
		oaj.setIncludeOwned(false);
		assertFalse(oaj.getIncludeOwned());
		oaj.setIncludeOwned(true);
		assertTrue(oaj.getIncludeOwned());

		assertFalse(oaj.getIncludeAll());
		oaj.setIncludeAll(false);
		assertFalse(oaj.getIncludeAll());
		oaj.setIncludeAll(true);
		assertTrue(oaj.getIncludeAll());
	}

	@Test
	public void jsonTest() throws Exception {
		MessageSource ms = new MessageSource();
		ms.setId(7777);

		OAJson oaj = new OAJson();

		String json = oaj.write(ms);

		reset();

		MessageSource ms2 = oaj.readObject(json, MessageSource.class, false);

		String json2 = oaj.write(ms2);
		assertEquals(json, json2);
	}

	@Test
	public void json2Test() throws Exception {
		reset();
		MessageSource ms = new MessageSource();
		ms.setId(3);

		//qqqqq		ms.getMessageTypes().add(new MessageType());

		MessageTypeRecord mtr = new MessageTypeRecord();
		ms.getMessageTypeRecords().add(mtr);

		OAJson oaj = new OAJson();
		oaj.setIncludeOwned(true);
		String json = oaj.write(ms);

		OAObjectCacheDelegate.setUnitTestMode(true);
		OAObjectCacheDelegate.resetCache();

		MessageSource ms2 = oaj.readObject(json, MessageSource.class, false);

		assertEquals(ms2, ms2.getMessageTypeRecords().getAt(0).getMessageSource());

		String json2 = oaj.write(ms2);
		// assertEquals(json, json2);
	}

	@Test
	public void hubChangesTest() throws Exception {
		reset();

		MessageSource ms = new MessageSource();
		ms.setId(3);

		for (int i = 0; i < 10; i++) {
			MessageType mt = new MessageType();
			mt.setName("test" + i);
			ms.getMessageTypes().add(mt);
		}

		ms.saveAll();

		OAJson oaj = new OAJson();
		String json = oaj.write(ms);

		JsonNode node = oaj.readTree(json);

		String json2 = node.toPrettyString();

		MessageSource ms2 = oaj.readObject(json2, MessageSource.class, false);

		assertEquals(10, ms2.getMessageTypes().size());

		ArrayNode nodeArray = (ArrayNode) node.get(MessageSource.P_MessageTypes);
		assertNotNull(nodeArray);

		nodeArray.remove(5);

		json2 = node.toPrettyString();

		ms2 = oaj.readObject(json2, MessageSource.class, false);

		assertEquals(ms, ms2);

		assertEquals(9, ms2.getMessageTypes().size());
	}

	@Test
	public void convertMethodParams() throws Exception {
		Method m = this.getClass().getMethod("testMethod", MessageSource.class, MessageType.class, int.class, OADateTime.class);

		assertNotNull(m);

		Object[] objs = new Object[] { new MessageSource(), new MessageType(), 0, new OADateTime() };

		String json = OAJson.convertMethodArgumentsToJson(m, objs, null, null);

		Object[] args = OAJson.convertJsonToMethodArguments(json, m);
		String json2 = OAJson.convertMethodArgumentsToJson(m, args, null, null);

		assertEquals(json, json2);
	}

	@Test
	public void includePropertyPathsTest() throws Exception {
		reset();
		MessageSource ms = new MessageSource();
		ms.setId(3);

		for (int i = 0; i < 10; i++) {
			MessageType mt = new MessageType();
			mt.setName("test" + i);
			ms.getMessageTypes().add(mt);
		}

		ms.saveAll();

		OAJson oaj = new OAJson();
		oaj.setIncludeOwned(false);

		oaj.addPropertyPath(MessageSourcePP.messageTypeRecords().pp);

		oaj.addPropertyPath(MessageSourcePP.messageTypes().messageSource().messageTypes().pp);

		String json = oaj.write(ms);
	}

	public void testMethod(MessageSource ms, MessageType mt, int i1, OADateTime dt) {
	}

	@Test
	public void hubChangesTest2() throws Exception {
		reset();

		MessageSource ms = new MessageSource();
		ms.setId(3);

		for (int i = 0; i < 10; i++) {
			MessageType mt = new MessageType();
			mt.setName("test" + i);
			ms.getMessageTypes().add(mt);
		}

		ms.saveAll();

		OAJson oaj = new OAJson();
		String json = oaj.write(ms);

		JsonNode node = oaj.readTree(json);

		String json2 = node.toPrettyString();

		MessageSource ms2 = oaj.readObject(json2, MessageSource.class, false);

		assertEquals(10, ms2.getMessageTypes().size());

		ArrayNode nodeArray = (ArrayNode) node.get(MessageSource.P_MessageTypes);
		assertNotNull(nodeArray);

		JsonNode jn = nodeArray.remove(5);
		nodeArray.add(jn);

		json2 = node.toPrettyString();

		ms2 = oaj.readObject(json2, MessageSource.class, false);

		assertEquals(ms, ms2);

		assertEquals(10, ms2.getMessageTypes().size());

		MessageType mt = ms2.getMessageTypes().get(9);
		assertEquals("test5", mt.getName());
	}

}