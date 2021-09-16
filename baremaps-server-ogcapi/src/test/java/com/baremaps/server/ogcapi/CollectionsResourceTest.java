/*
 * Copyright (C) 2020 The Baremaps Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.baremaps.server.ogcapi;

import static org.junit.Assert.assertEquals;

import com.baremaps.model.Collection;
import com.baremaps.model.Collections;
import com.baremaps.model.Link;
import com.baremaps.testing.IntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.jackson2.Jackson2Plugin;
import org.junit.Test;
import org.junit.experimental.categories.Category;

public class CollectionsResourceTest extends JerseyTest {

  Jdbi jdbi;

  @Override
  protected ResourceConfig configure() {
    enable(TestProperties.LOG_TRAFFIC);
    enable(TestProperties.DUMP_ENTITY);

    // Create a connection to a throwaway postgres database
    Connection connection;
    try {
      connection = DriverManager.getConnection("jdbc:tc:postgresql:13:///test");
    } catch (SQLException throwables) {
      throw new RuntimeException("Unable to connect to the database");
    }

    // Initialize the database
    jdbi = Jdbi.create(connection).installPlugin(new Jackson2Plugin());
    jdbi.useHandle(
        handle ->
            handle.execute(
                "create table collections (id uuid primary key, title text, description text, links jsonb[] default '{}'::jsonb[], extent jsonb, item_type text default 'feature', crs text[])"));

    // Configure the service
    return new ResourceConfig()
        .register(CollectionsResource.class)
        .register(
            new AbstractBinder() {
              @Override
              protected void configure() {
                bind(jdbi).to(Jdbi.class);
              }
            });
  }

  @Test
  @Category(IntegrationTest.class)
  public void test() {
    // Create a new collection
    Collection collection = new Collection();
    collection.setTitle("test");
    collection.setLinks(List.of());

    Link link = new Link();
    link.setHref("/link");
    link.setRel("self");

    collection.setLinks(List.of(link));

    // List the collections
    Collections collections = target().path("/collections").request().get(Collections.class);
    assertEquals(0, collections.getCollections().size());

    // Insert the collection
    Response response =
        target()
            .path("/collections")
            .request(MediaType.APPLICATION_JSON)
            .post(Entity.entity(collection, MediaType.valueOf("application/json")));
    assertEquals(201, response.getStatus());

    // List the collections
    collections = target().path("/collections").request().get(Collections.class);
    assertEquals(1, collections.getCollections().size());

    // Get the collection
    String id = response.getHeaderString("Location").split("/")[4];
    collection = target().path("/collections/" + id).request().get(Collection.class);
    assertEquals("test", collection.getTitle());

    // Update the collection
    collection.setTitle("test_update");
    response =
        target()
            .path("/collections/" + id)
            .request()
            .put(Entity.entity(collection, MediaType.valueOf("application/json")));
    assertEquals(204, response.getStatus());

    // Delete the collection
    response = target().path("/collections/" + id).request().delete();
    assertEquals(204, response.getStatus());

    // List the collections
    collections = target().path("/collections").request().get(Collections.class);
    assertEquals(0, collections.getCollections().size());
  }
}
