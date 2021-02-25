package net.zylklab.bigdata.nifi.record_converter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SandBox {
	public static void main(String[] args) throws JsonParseException, JsonMappingException, IOException {
		String s = "{\"geoip_iso_country_code\": \"MX\",\"rrr\": \"QQQ\",\"kkkk\": \"hhhh\"}";
		ObjectMapper objectMapper = new ObjectMapper();
		TypeReference<HashMap<String,String>> typeRef = new TypeReference<HashMap<String,String>>() {};
		Map<String, String> a = objectMapper.readValue(s, typeRef);
		
		System.out.println(a.size());
		
		HashMap<String, String> b = new HashMap<>();
		b.put("kkk", "cccc");
		b.put("kkk1", "cccc2");
		String kkk = objectMapper.writeValueAsString(b);
		System.out.println(kkk);
		
		
	}
}
