/*
 * Copyright 1999–2025 ViaOA (info@viaoa.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.viaoa.json.jackson;

import java.io.IOException;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.viaoa.datetime.OATime;

/**
 * Jackson {@link com.fasterxml.jackson.databind.JsonDeserializer JsonDeserializer}
 * for {@link OATime}.
 * <p>
 * This deserializer expects the JSON value to be a {@code String} using
 * {@link OATime#JsonFormat}. The text is converted into an {@link OATime}
 * instance when loading OA-aware objects or POJOs through a Jackson
 * {@code ObjectMapper}.
 * </p>
 * <p>
 * The class is stateless and thread-safe. It is normally installed
 * automatically via {@link OAJacksonModule}.
 * </p>
 */
public class OATimeDeserializer extends JsonDeserializer<OATime> {

	/**
	 * Deserializes a JSON value into an {@link OATime}.
	 * <p>
	 * Reads the current JSON token as text. If the text is {@code null}, returns
	 * {@code null}. Otherwise, constructs an {@link OATime} using the configured
	 * {@link OATime#JsonFormat}.
	 *
	 * @param jp    the JSON parser positioned at the value to read
	 * @param ctxt  deserialization context supplied by Jackson
	 * @return      the parsed {@link OATime}, or {@code null} if the JSON value is null
	 * @throws IOException      if an I/O error occurs while reading
	 * @throws JacksonException if Jackson encounters a parsing error
	 */
	@Override
	public OATime deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException, JacksonException {
		String s = jp.getText();
		if (s == null) {
			return null;
		}

		OATime t = new OATime(s, OATime.JsonFormat);

		return t;
	}
}
