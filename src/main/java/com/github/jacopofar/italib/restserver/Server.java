package com.github.jacopofar.italib.restserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.jacopofar.italib.ItalianModel;
import com.github.jacopofar.italib.ItalianVerbConjugation;
import com.github.jacopofar.italib.postagger.POSUtils;
import opennlp.tools.util.Span;
import spark.Response;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static spark.Spark.*;

/**
 * Exposes the Italian NLP library functions as REST services
 */
public class Server {
    private static ItalianModel im = null;

    public static void main(String[] args) throws IOException {

        System.out.println("Loading model...");
        try {
            im = new ItalianModel();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            System.exit(1);
        } catch (SQLException e) {
            System.exit(2);
            e.printStackTrace();
        }

        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");

        port(5678);

        //show exceptions in console and HTTP responses
        exception(Exception.class, (exception, request, response) -> {
            //show the exceptions using stdout
            System.out.println("Exception:");
            exception.printStackTrace(System.out);
            response.status(400);
            response.body(exception.getMessage());
        });
        /**
         * Annotate only a given list of POS, marking them with no other annotations
         * */
        post("/posmatch", (request, response) -> {
            ObjectMapper mapper = new ObjectMapper();
            AnnotationRequest ar = mapper.readValue(request.body(), AnnotationRequest.class);
            if(ar.errorMessages().size() != 0){
                response.status(400);
                return "invalid request body. Errors: " + ar.errorMessages() ;
            }
            String tagRegex = ar.getParameter();
            HashSet<String> acceptedTags = new HashSet<>();
            for(String tag:POSUtils.getPossibleTags()){
                if(tag.matches(tagRegex))
                    acceptedTags.add(tag);
            }

            if(acceptedTags.isEmpty())
                throw new RuntimeException("tag regex "+tagRegex+" not recognized, possible tags: " + Arrays.toString(POSUtils.getPossibleTags()));
            Span[] spans = im.getPosTags(ar.getText());
            List<Annotation> anns = new ArrayList<>();

            for(Span s:spans){
                if(acceptedTags.contains(s.getType())){
                   anns.add(new Annotation(s.getStart(), s.getEnd()));
                }
            }

            return sendAnnotations(anns, response);
        });

        /**
         * Annotate all of the tokens with the tag type as the annotation
         * */
        post("/postagger", (request, response) -> {
            ObjectMapper mapper = new ObjectMapper();
            AnnotationRequest ar = mapper.readValue(request.body(), AnnotationRequest.class);
            if(ar.errorMessages().size() != 0){
                response.status(400);
                return "invalid request body. Errors: " + ar.errorMessages() ;
            }

            Span[] spans = im.getPosTags(ar.getText());
            List<Annotation> anns = new ArrayList<>();

            for(Span s:spans){
                ObjectNode posNote = JsonNodeFactory.instance.objectNode();
                posNote.put("POS",s.getType());
                anns.add(new Annotation(s.getStart(), s.getEnd(), posNote));
            }

            return sendAnnotations(anns, response);
        });

        /**
         * List the POS tags used by the library
         * */
        get("/pos", (request, response) -> {
            ArrayNode annotationArray =  JsonNodeFactory.instance.arrayNode();
            for(String tag:POSUtils.getPossibleTags()){
                ObjectNode POS = JsonNodeFactory.instance.objectNode();
                POS.put("tag", tag);
                POS.put("description", POSUtils.getDescription(tag));
                annotationArray.add(POS);
            }
            response.type("application/json");
            return annotationArray.toString();
        });

        /**
         * Annotate the verbs matching the given congjugations
         * */
        post("/verbconjugations", (request, response) -> {
            ObjectMapper mapper = new ObjectMapper();
            AnnotationRequest ar = mapper.readValue(request.body(), AnnotationRequest.class);
            if(ar.errorMessages().size() != 0){
                response.status(400);
                return "invalid request body. Errors: " + ar.errorMessages() ;
            }
            VerbFilter vf = null;
            if(ar.getParameter().startsWith("{")){
                vf = mapper.readValue(ar.getParameter(), VerbFilter.class);
            }
            else{
                vf = new VerbFilter(ar.getParameter());
            }


            HashSet<CharSequence> accepted=new HashSet<> ();
            ItalianVerbConjugation v = new ItalianVerbConjugation(im);
            v.setInfinitive(vf.getInfinitive());
            for(String mode:ItalianVerbConjugation.getImpersonalModes()){
                if(vf.getMode() != null && !vf.getMode().equals(mode))
                    continue;
                v.setMode(mode);
                accepted.add(v.getConjugated());
            }
            for(String mode:ItalianVerbConjugation.getPersonalModes()){
                if(vf.getMode() != null && !vf.getMode().equals(mode))
                    continue;
                for(int person:new Integer[]{1,2,3}){
                    if(vf.getPerson() != null && vf.getPerson() != person)
                        continue;
                    for(char num:new Character[]{'s','p'}){
                        if(vf.getNumber() != null && !vf.getNumber().equals(num+""))
                            continue;
                        if(mode.equals("imperative") && person==1 && num=='s')
                            continue;
                        v.setMode(mode);
                        v.setNumber(num);
                        v.setPerson(person);
                        accepted.add(v.getConjugated());
                    }
                }
            }
            List<Annotation> anns = new ArrayList<>();
            System.out.println(accepted.toString());
            for(Span token:im.getTokens(ar.getText())){
                System.out.println("esamino '"+token.getCoveredText(ar.getText().toLowerCase())+"'");
                if(accepted.contains(token.getCoveredText(ar.getText().toLowerCase()))){
                    anns.add(new Annotation(token.getStart(),token.getEnd()));
                }
            }
            return sendAnnotations(anns, response);
        });



    }
    private static String sendAnnotations( List<Annotation> list, Response res){
        JsonNodeFactory nodeFactory = JsonNodeFactory.instance;
        ObjectNode retVal = nodeFactory.objectNode();
        ArrayNode annotationArray = retVal.putArray("annotations");

        for(Annotation ann:list){
            ObjectNode annotation = nodeFactory.objectNode();
            annotation.put("span_start", ann.getStart());
            annotation.put("span_end", ann.getEnd());
            if(ann.getAnnotation() != null){
                annotation.set("annotation", ann.getAnnotation());
            }
            annotationArray.add(annotation);
        }
        res.type("application/json");
        return retVal.toString();
    }
}