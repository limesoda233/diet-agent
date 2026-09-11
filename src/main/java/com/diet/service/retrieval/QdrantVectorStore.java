package com.diet.service.retrieval;

import com.diet.enums.SourceMode;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;

public class QdrantVectorStore implements VectorStore {
    private final RetrievalProperties.Qdrant config;
    private final int dimensions;
    private final String model;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    public QdrantVectorStore(RetrievalProperties.Qdrant config, int dimensions, String model, HttpClient http) {
        this.config=config; this.dimensions=dimensions; this.model=model; this.http=http;
        if (!config.getCollection().matches("diet_[a-zA-Z0-9_]{1,120}")) throw new IllegalArgumentException("Qdrant collection 必须使用 diet_ 前缀及合法名称");
    }
    @Override public void ensureCollection() {
        if (dimensions <= 0 || model.isBlank()) throw new RetrievalDependencyException("embedding_not_configured");
        var info = call("GET", "", null, true);
        if (info == null) {
            call("PUT", "", Map.of("vectors",Map.of("size",dimensions,"distance","Cosine"),"metadata",metadata(false)),false);
            for (String key : RetrievalSlots.NAMES) payloadIndex(key,"keyword");
            for (String key : List.of("sourceType","ingredientIds")) payloadIndex(key,"keyword");
            payloadIndex("ownerUserId","integer"); payloadIndex("ingredientDataPresent","bool");
        } else validate(info);
    }
    private Map<String,Object> metadata(boolean ready) { return Map.of("dietModel",model,"dietTemplate",MealDocument.TEMPLATE_VERSION,"dietReady",ready); }
    private void payloadIndex(String key, String type) { call("PUT","/index?wait=true",Map.of("field_name",key,"field_schema",type),false); }
    private void validate(JsonNode info) {
        var configNode=info.path("result").path("config"); var vectors=configNode.path("params").path("vectors");
        var metadata=configNode.path("metadata");
        if (vectors.path("size").asInt()!=dimensions || !vectors.path("distance").asText().equals("Cosine")
                || !metadata.path("dietModel").asText().equals(model) || !metadata.path("dietTemplate").asText().equals(MealDocument.TEMPLATE_VERSION))
            throw new RetrievalDependencyException("qdrant_collection_incompatible");
    }
    @Override public boolean isReady() {
        var info=call("GET","",null,true); if(info==null) return false;
        validate(info); return info.path("result").path("config").path("metadata").path("dietReady").asBoolean();
    }
    @Override public void markReady() { call("PATCH","",Map.of("metadata",metadata(true)),false); }
    @Override public void upsert(MealDocument document,float[] vector) {
        validateVector(vector);
        call("PUT","/points?wait=true",Map.of("points",List.of(Map.of("id",document.meal().id(),"vector",vector,"payload",document.payload()))),false);
    }
    @Override public void delete(long id) { call("POST","/points/delete?wait=true",Map.of("points",List.of(id)),false); }
    @Override public List<Hit> search(MealRetrievalRequest request,float[] vector,int limit) {
        validateVector(vector);
        var must=new ArrayList<Object>(); var mustNot=new ArrayList<Object>();
        must.add(match("sourceType",request.sourceMode().name()));
        must.add(match("ownerUserId",request.sourceMode()==SourceMode.PUBLIC?0L:request.userId()));
        if (!request.slots().mealTime().isEmpty()) must.add(any("mealTime",request.slots().mealTime()));
        if (!request.excludeMealIds().isEmpty()) mustNot.add(Map.of("has_id",request.excludeMealIds()));
        var constraints=request.constraints();
        if (!constraints.requiredIngredients().isEmpty() || !constraints.excludedIngredients().isEmpty()) must.add(match("ingredientDataPresent",true));
        constraints.requiredIngredients().forEach(value->must.add(match("ingredientIds",value)));
        if (!constraints.excludedIngredients().isEmpty()) mustNot.add(any("ingredientIds",constraints.excludedIngredients()));
        constraints.requiredSlots().forEach((key,values)->must.add(any(key,values)));
        constraints.excludedSlots().forEach((key,values)->mustNot.add(any(key,values)));
        var result=call("POST","/points/query",Map.of("query",vector,"filter",Map.of("must",must,"must_not",mustNot),"limit",Math.min(50,limit),"with_payload",List.of("contentHash")),false).path("result").path("points");
        if (!result.isArray() || result.size()>Math.min(50,limit)) throw new RetrievalDependencyException("qdrant_response_invalid");
        var hits=new ArrayList<Hit>(); var ids=new HashSet<Long>();
        for(var item:result) {
            long id=item.path("id").asLong(); double score=item.path("score").asDouble(Double.NaN);
            String hash=item.path("payload").path("contentHash").asText();
            if(!item.path("id").isIntegralNumber() || id<=0 || !ids.add(id) || !Double.isFinite(score) || !hash.matches("[0-9a-f]{64}")) throw new RetrievalDependencyException("qdrant_response_invalid");
            hits.add(new Hit(id,score,hash));
        }
        return List.copyOf(hits);
    }
    private static Object match(String key,Object value) { return Map.of("key",key,"match",Map.of("value",value)); }
    private static Object any(String key,List<String> values) { return Map.of("key",key,"match",Map.of("any",values)); }
    private void validateVector(float[] vector) {
        if(vector.length!=dimensions) throw new RetrievalDependencyException("qdrant_vector_dimension");
        double norm=0;
        for(float value:vector) { if(!Float.isFinite(value)) throw new RetrievalDependencyException("qdrant_vector_invalid"); norm+=(double)value*value; }
        if(norm==0) throw new RetrievalDependencyException("qdrant_vector_invalid");
    }
    private JsonNode call(String method,String suffix,Object body,boolean allowMissing) {
        try {
            URI base=HttpEmbeddingClient.safeEndpoint(config.getEndpoint());
            var request=HttpRequest.newBuilder(URI.create(base.toString().replaceAll("/$", "")+"/collections/"+config.getCollection()+suffix))
                    .timeout(config.getTimeout()).header("Content-Type","application/json");
            if(!config.getApiKey().isBlank()) request.header("api-key",config.getApiKey());
            request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==404 && allowMissing) return null;
            if(response.statusCode()<200 || response.statusCode()>=300) throw new RetrievalDependencyException("qdrant_http_"+response.statusCode());
            return json.readTree(response.body());
        } catch(RetrievalDependencyException e) { throw e;
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RetrievalDependencyException("qdrant_interrupted");
        } catch(Exception e) { throw new RetrievalDependencyException("qdrant_invalid_response_or_connection"); }
    }
}
