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
/**
 * Jackson serialization and deserialization support for OA framework types.
 * <p>
 * This package provides the concrete {@link com.fasterxml.jackson.databind.JsonSerializer}
 * and {@link com.fasterxml.jackson.databind.JsonDeserializer} implementations
 * used when mapping OA types to and from JSON using Jackson. It includes
 * serializers and deserializers for:
 * </p>
 * <ul>
 *   <li>{@code OAObject} graphs</li>
 *   <li>{@code OADate}, {@code OATime}, and {@code OADateTime}</li>
 *   <li>Embedded JSON string values</li>
 * </ul>
 *
 * <h2>Integration</h2>
 * <p>
 * The {@link OAJacksonModule} class registers all OA-specific serializers and
 * deserializers with a Jackson {@code ObjectMapper}. This allows any OAObject
 * graph or temporal value to be round-tripped through JSON while preserving:
 * </p>
 * <ul>
 *   <li>object identity,</li>
 *   <li>GUID and primary-key semantics,</li>
 *   <li>property-path filtering,</li>
 *   <li>lazy-loading compatibility.</li>
 * </ul>
 *
 * <h2>Design Notes</h2>
 * <ul>
 *   <li>All implementations are stateless and thread-safe.</li>
 *   <li>Formatting is delegated to the OA temporal classes.</li>
 *   <li>Used internally by {@link com.viaoa.json.OAJson} to construct
 *       OA-aware object mappers.</li>
 * </ul>
 */
package com.viaoa.json.jackson;
