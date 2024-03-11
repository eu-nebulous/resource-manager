package eu.nebulous.resource.discovery.broker_communication;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Assuming that only one SALCommunicator will exist - otherwise some variables should have their characterization as 'static' be removed
 */
public class SALCommunicator {

    private static String sal_host = "localhost";
    private static String sal_port = "9000";

    private static String mylogin = "admin";
    private static String mypassword = "admin";


    public static void register_device(int cpu_cores, int ram_gb, int disk, String application_name, String internal_ip_address, String external_ip_address, String city, String country, String latitude, String longitude){



    }

    public static String get_connection_id(String sal_host, int sal_port, String sal_username, String sal_password){
        // Request 1 - Get sessionID
        HashMap<String,String> authentication_map = new HashMap<>();
        authentication_map.put("username",sal_username);
        authentication_map.put("password",sal_password);
        String sessionID = sendPOSTRequest("http://"+sal_host+":"+sal_port+"/sal/pagateway/connect", new HashMap<>(), authentication_map);
        Logger.getGlobal().log(Level.INFO,"Retrieved session id "+sessionID);
        return sessionID;
    }

    public static void main(String[] args) {




        //String contentType = "application/json";

        String sessionID = get_connection_id("localhost",9000,"admin","admin");
        Logger.getGlobal().log(Level.INFO,"The session id is "+sessionID);
        ArrayList<String> applications = get_running_applications(request_running_applications_REST(sessionID));
        Logger.getGlobal().log(Level.INFO,"The running apps are "+applications.toString());

        register_devices("./src/main/java/eu/nebulous/resource/discovery/broker_communication/sal_device_registration_base_payload.json", sessionID, applications,"10.100.100","100.100.100.",10,10,10,"test12","test_provider","Athens","Greece",1);
        // Request 4
        //String payload4 = "{\"key3\": \"value3\"}";
        //sendRequest("https://api.example.com/endpoint3", sessionID, contentType, payload4);
    }

    public static String request_running_applications_AMQP() {
        // Request 2 - Get available jobs
        //String get_jobs_string = sendGETRequest("http://localhost:9000/sal/job/" );
        //return  get_jobs_string;
        return null;
    }

        private static String request_running_applications_REST(String sessionID) {

        // Request 2 - Get available jobs
        String get_jobs_payload = "{\"sessionid\": \""+sessionID+"\"}";
        HashMap<String,String> session_id_headers = new HashMap<>();
        session_id_headers.put("sessionid",sessionID);
        Logger.getGlobal().log(Level.INFO,"Using temporary \"job\" endpoint to get the jobs from SAL...");
        String get_jobs_string = sendGETRequest("http://localhost:9000/sal/job/",session_id_headers );
        return  get_jobs_string;
    }

    private static void register_devices(String request_body_file, String sessionID, ArrayList<String> applications,String internal_ip_address, String external_ip_address, int cpu_cores, int ram_gb, int disk_gb, String device_name,String provider_id, String city_name, String country_name, int number_of_devices_to_register) {

        for (int counter = 0; counter < number_of_devices_to_register; counter++) {
            JSONObject json = JsonFileParser.parse(request_body_file);
            if (number_of_devices_to_register>1) { //Test mode, TODO delete

                json.put("name", "test" + counter);

                ((JSONObject) ((JSONArray) json.get("ipAddresses")).get(0)).put("value", internal_ip_address + counter);
                ((JSONObject) json.get("nodeProperties")).put("disk", new Random().nextInt(1, 101));
                ((JSONObject) json.get("nodeProperties")).put("memory", new Random().nextInt(1, 17));
                ((JSONObject) json.get("nodeProperties")).put("providerId", String.valueOf(new Random().nextInt(1, 21)));
                ((JSONObject) json.get("nodeProperties")).put("numberOfCores", new Random().nextInt(1, 17));

                String[] country_choices = {"Greece", "Poland", "France"};
                String[] city_choices = {"Athens", "Warsaw", "Nice"};
                int random_int = new Random().nextInt(0, 3);
                ((JSONObject) ((JSONObject) json.get("nodeProperties")).get("geoLocation")).put("country", country_choices[random_int]);
                ((JSONObject) ((JSONObject) json.get("nodeProperties")).get("geoLocation")).put("city", city_choices[random_int]);
                ((JSONObject) ((JSONObject) json.get("nodeProperties")).get("geoLocation")).put("latitude", new Random().nextFloat(-90, 90));
                ((JSONObject) ((JSONObject) json.get("nodeProperties")).get("geoLocation")).put("longitude", new Random().nextFloat(-90, 90));
                // Request 3 - Register device for a particular job
            }else{
                json.put("name", device_name);
                ((JSONObject) ((JSONArray) json.get("ipAddresses")).get(0)).put("value", internal_ip_address);
                ((JSONObject) ((JSONArray) json.get("ipAddresses")).get(1)).put("value", external_ip_address);
                ((JSONObject) json.get("nodeProperties")).put("disk", disk_gb);
                ((JSONObject) json.get("nodeProperties")).put("memory", ram_gb);
                ((JSONObject) json.get("nodeProperties")).put("providerId", provider_id);
                ((JSONObject) json.get("nodeProperties")).put("numberOfCores", cpu_cores);
                ((JSONObject) ((JSONObject) json.get("nodeProperties")).get("geoLocation")).put("country", country_name);
                ((JSONObject) ((JSONObject) json.get("nodeProperties")).get("geoLocation")).put("city", city_name);
                ((JSONObject) ((JSONObject) json.get("nodeProperties")).get("geoLocation")).put("latitude", new Random().nextFloat(-90, 90));
                ((JSONObject) ((JSONObject) json.get("nodeProperties")).get("geoLocation")).put("longitude", new Random().nextFloat(-90, 90));
            }

            System.out.println(json.toJSONString());

            for (String application : applications) {
                json.put("jobId", application);
                String payload3 = json.toJSONString();
                HashMap<String, String> headers = new HashMap<>();
                headers.put("sessionid", sessionID);
                headers.put("Content-Type", "application/json");
                sendPOSTRequest("http://" + sal_host + ":" + sal_port + "/sal/edge/" + application, headers, payload3);

            }
        }
    }


    public static String get_device_registration_json(String request_body_file,String internal_ip_address, String external_ip_address, int cpu_cores, int ram_gb, int disk_gb, String device_name,String provider_id, String city_name, String country_name) {

            JSONObject json = JsonFileParser.parse(request_body_file);
            json.put("name", device_name);
            ((JSONObject) ((JSONArray) json.get("ipAddresses")).get(0)).put("value", internal_ip_address);
            ((JSONObject) ((JSONArray) json.get("ipAddresses")).get(1)).put("value", external_ip_address);
            ((JSONObject) json.get("nodeProperties")).put("disk", disk_gb);
            ((JSONObject) json.get("nodeProperties")).put("memory", ram_gb);
            ((JSONObject) json.get("nodeProperties")).put("providerId", provider_id);
            ((JSONObject) json.get("nodeProperties")).put("numberOfCores", cpu_cores);
            ((JSONObject) ((JSONObject) json.get("nodeProperties")).get("geoLocation")).put("country", country_name);
            ((JSONObject) ((JSONObject) json.get("nodeProperties")).get("geoLocation")).put("city", city_name);
            ((JSONObject) ((JSONObject) json.get("nodeProperties")).get("geoLocation")).put("latitude", new Random().nextFloat(-90, 90));
            ((JSONObject) ((JSONObject) json.get("nodeProperties")).get("geoLocation")).put("longitude", new Random().nextFloat(-90, 90));
            return(json.toJSONString());
    }


    public static ArrayList<String> get_running_applications(String running_jobs_string) {

        ArrayList<String>applications = new ArrayList<>();
        JSONParser parser = new JSONParser();
        try{
            Object received_json = parser.parse(running_jobs_string);
            if (received_json instanceof JSONArray) {
                JSONArray jobs_array = (JSONArray) parser.parse(running_jobs_string);
                for (int i = 0; i < jobs_array.size(); i++) {
                    JSONObject json_job_object = (JSONObject) jobs_array.get(i);
                    applications.add((String) json_job_object.get("jobId"));
                }
            }else if (received_json instanceof JSONObject){
                JSONObject json_job_object = (JSONObject) received_json;
                applications.add((String) json_job_object.get("jobId"));
            }
        }catch (Exception e){
            e.printStackTrace();
            System.out.println("This is the input json jobs string\n\n\n\n");
            System.out.println(running_jobs_string);
        }
        return  applications;
    }

    private static String sendGETRequest(String url, HashMap<String,String>headers) {
        String response_string = "";
        CloseableHttpClient client = HttpClients.createDefault();
        HttpGet httpGet = new HttpGet(url);

        // Set headers
        for (Map.Entry<String,String> entry : headers.entrySet()) {
            httpGet.setHeader(entry.getKey(), entry.getValue());
        }

        CloseableHttpResponse response = null;
        try {
            response = client.execute(httpGet);
            HttpEntity responseEntity = response.getEntity();
            if (responseEntity != null) {
                InputStream inputStream = responseEntity.getContent();
                response_string = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    System.out.write(buffer, 0, bytesRead);
                }
            }
            response.close();
            client.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Status code: " + response.getStatusLine().getStatusCode());
        return response_string;
    }

    public static String sendPOSTRequest(String urlString, HashMap<String,String> headers, HashMap<String,String> multipart_form) {
        String response_string = "Invalid response";
        HttpPost post = new HttpPost(urlString);
        try {
            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            // Create a multipart entity
            if (!multipart_form.isEmpty()) {
                for (Map.Entry<String,String> entry : multipart_form.entrySet()) {
                    builder.addPart(entry.getKey(), new StringBody(entry.getValue(), ContentType.TEXT_PLAIN));
                }
                //post.setHeader("Content-Type", "multipart/form-data");
                HttpEntity entity = builder.build();
                post.setEntity(entity);
            }

            if (!headers.isEmpty()) {
                for (Map.Entry<String,String> entry : headers.entrySet()) {
                    post.setHeader(entry.getKey(), entry.getValue());
                }
            }


            CloseableHttpClient client = HttpClients.createDefault();


            HttpResponse response = client.execute(post);
            HttpEntity responseEntity = response.getEntity();
            if (responseEntity != null) {
                InputStream inputStream = responseEntity.getContent();
                response_string = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
                System.out.println("Printing before "+response_string);
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    System.out.write(buffer, 0, bytesRead);
                }
            }

        }catch (Exception e){
            e.printStackTrace();
        }
        System.out.println("Returning the response string "+response_string);
        return response_string;
    }



    public static String sendPOSTRequest(String urlString, HashMap<String,String> headers, String payload) {
        String response_string = "";
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(urlString);

        // Set headers
        if (!headers.isEmpty()) {
            for (Map.Entry<String,String> entry : headers.entrySet()) {
                httpPost.setHeader(entry.getKey(), entry.getValue());
            }
        }
        // Define JSON payload
        String jsonPayload = payload;
        StringEntity entity = null;
        try {
            entity = new StringEntity(jsonPayload);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        httpPost.setEntity(entity);

        // Execute the request
        CloseableHttpResponse response = null;
        try {
            response = httpclient.execute(httpPost);

            // Handle the response
            HttpEntity responseEntity = response.getEntity();
            response_string = EntityUtils.toString(responseEntity);
            // Close resources
            response.close();
            httpclient.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return response_string;
    }
}
