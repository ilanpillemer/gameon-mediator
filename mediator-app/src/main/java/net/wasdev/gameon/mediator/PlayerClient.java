/*******************************************************************************
 * Copyright (c) 2015 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package net.wasdev.gameon.mediator;

import java.io.StringReader;
import java.util.logging.Level;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.enterprise.context.ApplicationScoped;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.ResponseProcessingException;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

/**
 * A wrapped/encapsulation of outbound REST requests to the player service.
 * <p>
 * The URL for the player service is injected via CDI: {@code <jndiEntry />}
 * elements defined in server.xml maps the environment variable to the JNDI
 * value.
 * </p>
 * <p>
 * CDI will create this (the {@code PlayerClient} as an application scoped bean.
 * This bean will be created when the application starts, and can be injected
 * into other CDI-managed beans for as long as the application is valid.
 * </p>
 *
 * @see ApplicationScoped
 */
public class PlayerClient {

    /**
     * The player URL injected from JNDI via CDI.
     * 
     * @see {@code playerUrl} in
     *      {@code /mediator-wlpcfg/servers/gameon-mediator/server.xml}
     */
    @Resource(lookup = "playerUrl")
    String playerLocation;

    /**
     * The root target used to define the root path and common query parameters
     * for all outbound requests to the player service.
     *
     * @see WebTarget
     */
    WebTarget root;

    
    public class TheNotVerySensibleHostnameVerifier implements HostnameVerifier {
        public boolean verify(String string, SSLSession sslSession) {
            return true;
        }
    }
    
    /**
     * The {@code @PostConstruct} annotation indicates that this method should
     * be called immediately after the {@code PlayerClient} is instantiated
     * with the default no-argument constructor.
     *
     * @see PostConstruct
     * @see ApplicationScoped
     */
    @PostConstruct
    public void initClient() {
        try{
            Client client;
            ClientBuilder builder = ClientBuilder.newBuilder().sslContext(SSLContext.getDefault());
            if("development".equals(System.getenv("MAP_PLAYER_MODE"))){
                builder.hostnameVerifier(new TheNotVerySensibleHostnameVerifier());
            }
            client = builder.build().register(JacksonJsonProvider.class);
            this.root = client.target(playerLocation);
        }catch(Exception e){
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        Log.log(Level.FINER, this, "Player client initialized with {0}", playerLocation);
    }

    /**
     * Update the player's location. The new location will always be returned
     * from the service, in the face of a conflict between updates for the
     * player across devices, we'll get back the one that won.
     *
     * @param playerId
     *            The player id
     * @param jwt
     *            The server jwt for this player id.
     * @param oldRoomId
     *            The old room's id
     * @param newRoomId
     *            The new room's id
     * @return The id of the selected new room, taking contention into account.
     */
    public String updatePlayerLocation(String playerId, String jwt, String oldRoomId, String newRoomId) {
        WebTarget target = this.root.path("{playerId}/location").resolveTemplate("playerId", playerId).queryParam("jwt",
                jwt);

        JsonObject parameter = Json.createObjectBuilder().add("old", oldRoomId).add("new", newRoomId).build();

        Log.log(Level.FINER, this, "updating location using {0}", target.getUri().toString());

        try {
            // Make PUT request using the specified target, get result as a
            // string containing JSON
            JsonObject result = target.request(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .header("Content-type", "application/json").put(Entity.json(parameter), JsonObject.class);

            return result.getString("location");
        } catch (ResponseProcessingException rpe) {
            Response response = rpe.getResponse();
            Log.log(Level.FINER, this, "Exception changing player location,  uri: {0} resp code: {1} data: {2}",
                    target.getUri().toString(),
                    response.getStatusInfo().getStatusCode() + " " + response.getStatusInfo().getReasonPhrase(),
                    response.readEntity(String.class));

            Log.log(Level.FINEST, this, "Exception changing player location", rpe);
        } catch (ProcessingException | WebApplicationException ex) {
            Log.log(Level.FINEST, this, "Exception changing player location (" + target.getUri().toString() + ")", ex);
        }

        // Sadly, badness happened while trying to set the new room location
        // return to old room
        return oldRoomId;
    }
    
    /**
     * Get shared secret for player
     * @param playerId
     * @param jwt
     * @param oldRoomId
     * @param newRoomId
     * @return
     */
    public String getSharedSecret(String playerId, String jwt) {
        WebTarget target = this.root.path("{playerId}").resolveTemplate("playerId", playerId).queryParam("jwt",
                jwt);

        Log.log(Level.FINER, this, "requesting shared secret using {0}", target.getUri().toString());

        try {
            // Make PUT request using the specified target, get result as a
            // string containing JSON
            String result = target.request(MediaType.APPLICATION_JSON)//.accept(MediaType.APPLICATION_JSON)
                    .header("Content-type", "application/json").get(String.class);
            
            JsonReader p = Json.createReader(new StringReader(result));
            JsonObject j = p.readObject();

            return j.getString("apiKey");
        } catch (ResponseProcessingException rpe) {
            Response response = rpe.getResponse();
            Log.log(Level.FINER, this, "Exception obtaining shared secret for player,  uri: {0} resp code: {1} data: {2}",
                    target.getUri().toString(),
                    response.getStatusInfo().getStatusCode() + " " + response.getStatusInfo().getReasonPhrase(),
                    response.readEntity(String.class));

            Log.log(Level.FINEST, this, "Exception obtaining shared secret for player", rpe);
        } catch (ProcessingException | WebApplicationException ex) {
            Log.log(Level.FINEST, this, "Exception obtaining shared secret for player (" + target.getUri().toString() + ")", ex);
        } 

        // Sadly, badness happened while trying to get the shared secret
        return null;
    }

}
