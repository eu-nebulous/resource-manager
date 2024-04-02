package eu.nebulous.resource.discovery.broker_communication;


import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.io.FileReader;

public class JsonFileParser {
    public static JSONObject parse(String file_name){
        JSONParser parser = new JSONParser();
        try {
            Object obj = parser.parse(new FileReader(file_name));
            JSONObject jsonObject = (JSONObject) obj;
            // Access properties of the parsed JSON object here
            return jsonObject;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new JSONObject();
    }
    public static void main(String[] args) {
        String currentDir = System.getProperty("user.dir");
        System.out.println("Current Directory: " + currentDir);
        String parsed_file_string = parse("./src/main/java/eu/nebulous/resource/discovery/broker_communication/file.json").toString();
        System.out.println(parsed_file_string);
    }
}
