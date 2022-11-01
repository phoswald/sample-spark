package com.github.phoswald.sample.spark;

import static spark.Spark.delete;
import static spark.Spark.get;
import static spark.Spark.port;
import static spark.Spark.post;
import static spark.Spark.put;
import static spark.Spark.staticFiles;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.phoswald.sample.ConfigProvider;
import com.github.phoswald.sample.di.ApplicationModule;
import com.github.phoswald.sample.sample.EchoRequest;
import com.github.phoswald.sample.sample.SampleController;
import com.github.phoswald.sample.sample.SampleResource;
import com.github.phoswald.sample.task.TaskController;
import com.github.phoswald.sample.task.TaskEntity;
import com.github.phoswald.sample.task.TaskResource;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.xml.bind.JAXB;
import spark.QueryParamsMap;
import spark.Request;
import spark.Response;
import spark.Route;
import spark.Spark;

public class Application {

    private static final Logger logger = LogManager.getLogger();
    private static final Jsonb json = JsonbBuilder.create();

    private final int port;
    private final SampleResource sampleResource;
    private final SampleController sampleController;
    private final TaskResource taskResource;
    private final TaskController taskController;

    public Application( //
            ConfigProvider config, //
            SampleResource sampleResource, //
            SampleController sampleController, //
            TaskResource taskResource, //
            TaskController taskController) {
        this.port = Integer.parseInt(config.getConfigProperty("app.http.port").orElse("8080"));
        this.sampleResource = sampleResource;
        this.sampleController = sampleController;
        this.taskResource = taskResource;
        this.taskController = taskController;
    }

    public static void main(String[] args) {
        var module = new ApplicationModule() { };
        module.getApplication().start();
    }

    void start() {
        logger.info("sample-spark is starting, port=" + port);
        port(port);
        staticFiles.location("/resources");
        get("/app/rest/sample/time", (req, res) -> sampleResource.getTime());
        get("/app/rest/sample/config", (req, res) -> sampleResource.getConfig());
        post("/app/rest/sample/echo-xml", createXmlHandler(EchoRequest.class, reqBody -> sampleResource.postEcho(reqBody)));
        post("/app/rest/sample/echo-json", createJsonHandler(EchoRequest.class, reqBody -> sampleResource.postEcho(reqBody)));
        get("/app/pages/sample", createHtmlHandler(() -> sampleController.getSamplePage()));
        get("/app/rest/tasks", createJsonHandler(() -> taskResource.getTasks()));
        post("/app/rest/tasks", createJsonHandler(TaskEntity.class, req -> taskResource.postTasks(req)));
        get("/app/rest/tasks/:id", createJsonHandlerEx(req -> taskResource.getTask(req.params("id"))));
        put("/app/rest/tasks/:id", createJsonHandlerEx(TaskEntity.class, (req, reqBody) -> taskResource.putTask(req.params("id"), reqBody)));
        delete("/app/rest/tasks/:id", createJsonHandlerEx(req -> taskResource.deleteTask(req.params("id"))));
        get("/app/pages/tasks", createHtmlHandler(() -> taskController.getTasksPage()));
        post("/app/pages/tasks", createHtmlFormHandler(form -> taskController.postTasksPage(form.get("title").value(), form.get("description").value())));
        get("/app/pages/tasks/:id", createHtmlHandlerEx(req -> taskController.getTaskPage(req.params("id"), req.queryParams("action"))));
        post("/app/pages/tasks/:id", createHtmlFormHandlerEx((req, form) -> taskController.postTaskPage(req.params("id"), form.get("action").value(), form.get("title").value(), form.get("description").value(), form.get("done").value())));
    }

    void stop() {
        Spark.stop();
        Spark.awaitStop();
    }

    // TODO generate 404 if response body is null
    // TODO prevent returning body "null" when not found or deleted

    private static <R> Route createXmlHandler(Class<R> reqClass, Function<R, Object> callback) {
        return (req, res) -> {
            res.type("text/xml");
            return serializeXml(callback.apply(deserializeXml(reqClass, req.body())));
        };
    }

    private static <R> Route createJsonHandler(Supplier<Object> callback) {
        return (req, res) -> {
            res.type("application/json");
            return serializeJson(callback.get());
        };
    }

    private static <R> Route createJsonHandlerEx(Function<Request, Object> callback) {
        return (req, res) -> {
            res.type("application/json");
            return serializeJson(callback.apply(req));
        };
    }

    private static <R> Route createJsonHandler(Class<R> reqClass, Function<R, Object> callback) {
        return (req, res) -> {
            res.type("application/json");
            return serializeJson(callback.apply(deserializeJson(reqClass, req.body())));
        };
    }

    private static <R> Route createJsonHandlerEx(Class<R> reqClass, BiFunction<Request, R, Object> callback) {
        return (req, res) -> {
            res.type("application/json");
            return serializeJson(callback.apply(req, deserializeJson(reqClass, req.body())));
        };
    }

    private static Route createHtmlHandler(Supplier<Object> callback) {
        return (req, res) -> {
            res.type("text/html");
            return callback.get();
        };
    }

    private static Route createHtmlHandlerEx(Function<Request, Object> callback) {
        return (req, res) -> {
            res.type("text/html");
            return callback.apply(req);
        };
    }

    private static Route createHtmlFormHandler(Function<QueryParamsMap, Object> callback) {
        return (req, res) -> {
            res.type("text/html");
            QueryParamsMap form = req.queryMap();
            return callback.apply(form);
        };
    }

    private static Route createHtmlFormHandlerEx(BiFunction<Request, QueryParamsMap, Object> callback) {
        return (req, res) -> {
            res.type("text/html");
            QueryParamsMap form = req.queryMap();
            return sendHtmlOrRedirect(res, callback.apply(req, form));
        };
    }

    private static Object sendHtmlOrRedirect(Response res, Object result) {
        if(result instanceof String && ((String) result).startsWith("REDIRECT:")) { // TODO refactor redirect
        	res.redirect(((String) result).substring(9));
            return null;
        } else {
            res.type("text/html");
            return result;
        }
    }

    private static String serializeXml(Object object) {
        var buffer = new StringWriter();
        JAXB.marshal(object, buffer);
        return buffer.toString();
    }

    private static <T> T deserializeXml(Class<T> clazz, String text) {
        return JAXB.unmarshal(new StringReader(text), clazz);
    }

    private static String serializeJson(Object object) {
        return json.toJson(object);
    }

    private static <T> T deserializeJson(Class<T> clazz, String text) {
        return json.fromJson(text, clazz);
    }
}
