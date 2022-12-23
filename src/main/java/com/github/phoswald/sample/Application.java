package com.github.phoswald.sample;

import static spark.Spark.delete;
import static spark.Spark.get;
import static spark.Spark.port;
import static spark.Spark.post;
import static spark.Spark.put;
import static spark.Spark.staticFiles;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.phoswald.sample.sample.EchoRequest;
import com.github.phoswald.sample.sample.SampleController;
import com.github.phoswald.sample.sample.SampleResource;
import com.github.phoswald.sample.task.TaskController;
import com.github.phoswald.sample.task.TaskEntity;
import com.github.phoswald.sample.task.TaskResource;
import com.github.phoswald.sample.utils.ConfigProvider;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.xml.bind.JAXB;
import spark.QueryParamsMap;
import spark.Request;
import spark.Response;
import spark.Route;
import spark.Spark;

public class Application {

    private static final Logger logger = LoggerFactory.getLogger(Application.class);
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
        var module = new ApplicationModule();
        module.getApplication().start();
    }

    void start() {
        logger.info("sample-spark is starting, port=" + port);
        port(port);
        staticFiles.location("/resources");
        get("/app/rest/sample/time", createHandler(req -> sampleResource.getTime()));
        get("/app/rest/sample/config", createHandler(req -> sampleResource.getConfig()));
        post("/app/rest/sample/echo-xml", createXmlHandler(EchoRequest.class, (req, reqBody) -> sampleResource.postEcho(reqBody)));
        post("/app/rest/sample/echo-json", createJsonHandler(EchoRequest.class, (req, reqBody) -> sampleResource.postEcho(reqBody)));
        get("/app/pages/sample", createHtmlHandler(req -> sampleController.getSamplePage()));
        get("/app/rest/tasks", createJsonHandler(req -> taskResource.getTasks()));
        post("/app/rest/tasks", createJsonHandler(TaskEntity.class, (req, reqBody) -> taskResource.postTasks(reqBody)));
        get("/app/rest/tasks/:id", createJsonHandler(req -> taskResource.getTask(req.params("id"))));
        put("/app/rest/tasks/:id", createJsonHandler(TaskEntity.class, (req, reqBody) -> taskResource.putTask(req.params("id"), reqBody)));
        delete("/app/rest/tasks/:id", createJsonHandler(req -> taskResource.deleteTask(req.params("id"))));
        get("/app/pages/tasks", createHtmlHandler(req -> taskController.getTasksPage()));
        post("/app/pages/tasks", createHtmlHandler((req, form) -> taskController.postTasksPage(form.get("title").value(), form.get("description").value())));
        get("/app/pages/tasks/:id", createHtmlHandler(req -> taskController.getTaskPage(req.params("id"), req.queryParams("action"))));
        post("/app/pages/tasks/:id", createHtmlHandler((req, form) -> taskController.postTaskPage(req.params("id"), form.get("action").value(), form.get("title").value(), form.get("description").value(), form.get("done").value())));
    }

    void stop() {
        Spark.stop();
        Spark.awaitStop();
    }

    private static Route createHandler(Function<Request, Object> callback) {
        return (req, res) -> callback.apply(req);
    }

    private static <R> Route createXmlHandler(Class<R> reqClass, BiFunction<Request, R, Object> callback) {
        return (req, res) -> handleXml(res, () -> callback.apply(req, deserializeXml(reqClass, req.body())));
    }

    private static Object handleXml(Response res, Supplier<Object> callback) {
        Object result = callback.get();
        res.type("text/xml");
        return serializeXml(result);
    }

    private static Route createJsonHandler(Function<Request, Object> callback) {
        return (req, res) -> handleJson(res, () -> callback.apply(req));
    }

    private static <R> Route createJsonHandler(Class<R> reqClass, BiFunction<Request, R, Object> callback) {
        return (req, res) -> handleJson(res, () -> callback.apply(req, deserializeJson(reqClass, req.body())));
    }

    private static Object handleJson(Response res, Supplier<Object> callback) {
        Object result = callback.get();
        if(result == null) {
            res.status(404);
            return "";
        } else if(result instanceof String resultString) {
            return resultString;
        } else {
            res.type("application/json");
            return serializeJson(result);
        }
    }

    private static Route createHtmlHandler(Function<Request, Object> callback) {
        return (req, res) -> handleHtml(res, () -> callback.apply(req));
    }

    private static Route createHtmlHandler(BiFunction<Request, QueryParamsMap, Object> callback) {
        return (req, res) -> handleHtml(res, () -> callback.apply(req, req.queryMap()));
    }

    private static Object handleHtml(Response res, Supplier<Object> callback) {
        Object result = callback.get();
        if(result instanceof Path resultPath) {
        	res.redirect(resultPath.toString());
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
