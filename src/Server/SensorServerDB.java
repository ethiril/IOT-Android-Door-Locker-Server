package Server;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;
import java.io.*;
import java.sql.*;

@WebServlet("/SensorServerDB")
public class SensorServerDB extends HttpServlet {

	// check
	// https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/io/Serializable.html
	// for why this is important
	private static final long serialVersionUID = 1L;

	// Create new Gson instance
	private Gson gson = new Gson();
	// Default return message is blank
	private String returnMessage = "";
	private Connection conn = null;
	private Statement stmt;

	// Method to parse json file for db details
	public static JsonObject getDetails(String jsonFile) throws IOException {
		// get current directory
		String workDir = System.getProperty("user.dir");
		File file = new File(workDir, jsonFile);
		Gson gson = new Gson();
		// read the file from the path provided in the method, create a new Json Element
		JsonElement jElem = gson.fromJson(new FileReader(file.getPath()), JsonElement.class);
		// turn the element into a json object and return it
		JsonObject obj = jElem.getAsJsonObject();
		return obj;
	}

	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		// The init method runs during servlet load
		String user = ""; // Default values for user
		String pwd = ""; // password
		String url = ""; // and the url
		JsonObject dbDetails = null;
		try {
			// try and get the details for the db connection from file
			dbDetails = getDetails("/details.json");
		} catch (IOException e) {
			e.printStackTrace();
		}
		// if the details fetch didn't fail, either load the DB connection or do not
		// start the server
		user = dbDetails.get("username").toString().replaceAll("^\"|\"$", "");
		pwd = dbDetails.get("password").toString().replaceAll("^\"|\"$", "");
		url = dbDetails.get("url").toString().replaceAll("^\"|\"$", "") + user;
		// UserID & PWD driver
		try {
			Class.forName("com.mysql.jdbc.Driver").newInstance();
		} catch (Exception e) {
			System.out.println(e);
		}
		try {
			conn = DriverManager.getConnection(url, user, pwd);
			stmt = conn.createStatement();
		} catch (SQLException se) {
			System.out.println(se);
		}

		System.out.println("Server is online, listening . . .");
		System.out.println(
				"Upload sensor data with http://localhost:8080/15068126_Mobile_Dev_1CWK50_Server/SensorServerDB?sensordata=sensorJson");
		System.out.println(
				"View last sensor reading at  http://localhost:8080/15068126_Mobile_Dev_1CWK50_Server/SensorServerDB?getdata={\"retrieveMostRecent\":\"true\"} \n\n");
	}

	// close the connection
	public void destroy() {
		try {
			conn.close();
		} catch (SQLException se) {
			System.out.println(se);
		}
	}

	public SensorServerDB() {
		super();
	}

	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.setStatus(HttpServletResponse.SC_OK);
		SensorData SensorData = new SensorData();
		// This block of code fetches the most recent data either by room or in general
		String getdata = request.getParameter("getdata");
		if (getdata != null) {
			String resultsJson = null;
			// if the user wants to retrieve the most recent data for a particular room:
			if (getdata.contains("retrieveForRoom")) {
				// Parse the response
				JsonElement jElem = gson.fromJson(getdata, JsonElement.class);
				JsonObject obj = jElem.getAsJsonObject();
				// Remove the JSON syntax and return the room id
				int room = Integer.parseInt(obj.get("retrieveForRoom").toString().replaceAll("^\"|\"$", ""));
				String retrievedData = retrieveMostRecentAttemptForRoom(room);
				SensorData retrievedJson = gson.fromJson(retrievedData, SensorData.class);
				// if the room returned isn't 0 (default) then the data is correct
				if (retrievedJson.getRoomID() != 0) {
					resultsJson = retrievedData;
				} else {
					// otherwise return an error
					resultsJson = "{\"Response\":\"No data stored\"}";
				}
			}
			// if the user wants to retrieve the most recent data regardless of room:
			if (getdata.contains("retrieveMostRecent")) {
				String retrievedData = retrieveMostRecentAttempt();
				SensorData retrievedJson = gson.fromJson(retrievedData, SensorData.class);
				if (retrievedJson.getRoomID() != 0) {
					resultsJson = retrievedData;
				} else {
					resultsJson = "{\"Response\":\"No data stored\"}";
				}
			}
			PrintWriter out = response.getWriter();
			out.println(resultsJson);
			out.close();
		}

		// post the sensor data to the table when user accesses room
		String sensorJson = request.getParameter("sensordata");
		if (sensorJson != null) {
			SensorData = gson.fromJson(sensorJson, SensorData.class);
			if (SensorData.getUserID() != 0) {
				System.out.println("SensorJsonString: " + sensorJson + "SensorData: " + SensorData);
				PrintWriter out = response.getWriter();
				System.out.println("Trying to update for: " + SensorData);
				returnMessage = updateAccessLogs(SensorData);
				out.close();
			} else {
				returnMessage = "{\"response\":\"Erroneous data retrieved\"}";
				PrintWriter out = response.getWriter();
				out.println(returnMessage);
				out.close();
			}
		}

		String updateLockStatus = request.getParameter("updatelock");
		if (updateLockStatus != null) {
			SensorData = gson.fromJson(updateLockStatus, SensorData.class);
			int roomid = SensorData.getRoomID();
			String tagid = SensorData.getTagID();
			boolean locked = SensorData.getLocked();
			if (SensorData.getTagID() != null) {
				if (verifyRoomAccess(roomid, tagid)) {
					System.out.println("updateLockStatus: " + updateLockStatus + "SensorData: " + SensorData);
					PrintWriter out = response.getWriter();
					updateLockStatus(locked, roomid);
					returnMessage = "{\"access\":\"updated\"}";
					out.println(returnMessage);
					out.close();
				} else {
					returnMessage = "{\"access\":\"Verification failed, user does does not have the correct permissions.\"}";
					PrintWriter out = response.getWriter();
					out.println(returnMessage);
					out.close();
				}
			} else {
				returnMessage = "{\"response\":\"Erroneous data retrieved, no TagID\"}";
				PrintWriter out = response.getWriter();
				out.println(returnMessage);
				out.close();
			}
		}

		// updateLockStatus

		// Sample user array method, this will return every user in the database,
		// Could have other uses and could be fetched from the server
		String users = request.getParameter("getusers");
		if (users != null) {
			String usersList = getUserList();
			PrintWriter out = response.getWriter();
			out.print(usersList);
			out.close();
		}

		String usermatch = request.getParameter("getuser");
		if (usermatch != null) {
			SensorData = gson.fromJson(usermatch, SensorData.class);
			int matchUser = checkUserForTag(SensorData.getTagID());
			PrintWriter out = response.getWriter();
			out.print("{\"user\":\"" + matchUser + "\"}");
			out.close();
		}

		// Sample user details return, this should always have verification on top of it
		// as it is personal details
		// however for this coursework, that is unnecessary.
		String userdetails = request.getParameter("getuserdetails");
		if (userdetails != null) {
			SensorData = gson.fromJson(userdetails, SensorData.class);
			String userDetails = "{\"userdetails\":" + getUserDetails(SensorData.getUserID()) + "}";
			PrintWriter out = response.getWriter();
			out.print(userDetails);
			out.close();
		}

		// verify room access code block
		String accessverify = request.getParameter("verifyroomaccess");
		if (accessverify != null) {
			SensorData = gson.fromJson(accessverify, SensorData.class);
			String userVerification = "{\"access\":\"" + verifyRoomAccess(SensorData.getRoomID(), SensorData.getTagID())
					+ "\"}";
			PrintWriter out = response.getWriter();
			out.print(userVerification);
			out.close();
		}

		String getlockedstatus = request.getParameter("getlockstatus");
		if (getlockedstatus != null) {
			SensorData = gson.fromJson(getlockedstatus, SensorData.class);
			String lockVerification = "{\"locked\":\"" + retrieveLockStatus(SensorData.getRoomID()) + "\"}";
			PrintWriter out = response.getWriter();
			out.print(lockVerification);
			out.close();
		}

		String getroomid = request.getParameter("getroomid");
		if (getroomid != null) {
			SensorData = gson.fromJson(getroomid, SensorData.class);
			String roomID = "{\"RoomID\":" + getRoomID(SensorData.getRoomName()) + "}";
			PrintWriter out = response.getWriter();
			out.print(roomID);
			out.close();
		}
		
		String getrooms = request.getParameter("getrooms");
		if (getrooms != null) {
				String roomList = getRoomList();
				PrintWriter out = response.getWriter();
				out.print(roomList);
				out.close();
		}
		
		String getroomsbyserial = request.getParameter("getroombyserial");
		if (getroomsbyserial != null) {
				SensorData = gson.fromJson(getroomsbyserial, SensorData.class);
				String roomName = "{\"RoomName\":\"" + getRoomIDBySerial(SensorData.getSerialNum()) + "\"}";
				PrintWriter out = response.getWriter();
				out.print(roomName);
				out.close();
		}
		
	}

	// same as get
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		doGet(request, response);
	}

	// Update the access logs with data
	private String updateAccessLogs(SensorData SensorData) {
		try {
			String updateSQL = "insert into AccessLogs(UserID, RoomID, TagID, RoomName, SensorName, TimeInserted, Locked, SuccessFailure) "
					+ "values('" + Integer.toString(SensorData.getUserID()) + "','"
					+ Integer.toString(SensorData.getRoomID()) + "','" + SensorData.getTagID() + "','"
					+ SensorData.getRoomName() + "','" + SensorData.getSensorName() + "','"
					+ SensorData.getTimeInserted() + "'," + SensorData.getLocked() + ",'"
					+ SensorData.getSuccessFailure() + "');";
			System.out.println("DEBUG: Update: " + updateSQL);
			stmt.executeUpdate(updateSQL);
			System.out.println("DEBUG: Update successful ");
		} catch (SQLException se) {
			System.out.println(se);
			System.out.println("\nDEBUG: Update error - see error trace above for help. ");
			return "Invalid inputs";
		}
		return "Updated Logs";
	}

	private String updateLockStatus(boolean LockStatus, int RoomID) {
		try {
			String updateSQL = "update Rooms set Locked = " + LockStatus + " where RoomID = " + RoomID + ";";
			System.out.println("DEBUG: Update: " + updateSQL);
			stmt.executeUpdate(updateSQL);
			System.out.println("DEBUG: Update successful ");
		} catch (SQLException ex) {
			System.out.println(ex);
			System.out.println("\nDEBUG: Update error - see error trace above for help. ");
		}
		return "Updated Locked status";

	}

	private int checkUserForTag(String tagid) {
		String sql = "SELECT UserID FROM Tags WHERE TagID='" + tagid + "'";
		ResultSet rs;
		try {
			rs = stmt.executeQuery(sql);
			while (rs.next()) {
				int user = rs.getInt("UserID");
				if (user != 0) {
					return user;
				} else {
					return 0;
				}
			}
		} catch (SQLException ex) {
			System.out.println("Error In SQL " + ex.getMessage());
			return 0;
		}
		// default
		return 0;
	}

	private Boolean verifyRoomAccess(int roomid, String tagid) {
		int user = checkUserForTag(tagid);
		if (user != 0) {
			String sql = "SELECT * FROM AccessList WHERE UserID='" + Integer.toString(user) + "' AND RoomID ='"
					+ Integer.toString(roomid) + "'";
			ResultSet rs;
			try {
				rs = stmt.executeQuery(sql);
				while (rs.next()) {
					if (rs.getInt("UserID") != 0) {
						return true;
					} else {
						System.out.println("User does not have access");
						return false;
					}
				}
			} catch (SQLException ex) {
				System.out.println("Error In SQL " + ex.getMessage());
				return false;
			}
			// default
			return false;
		} else {
			System.out.println("Selected user and tag combo either do not exist or do not have access to this room");
			return false;
		}
	}

	private int getRoomID(String roomname) {
		String sql = "SELECT RoomID FROM Rooms WHERE RoomName='" + roomname + "'";
		ResultSet rs;
		try {
			rs = stmt.executeQuery(sql);
			int room = 0;
			while (rs.next()) {
				room = rs.getInt("RoomID");
			}
			return room;
		} catch (SQLException ex) {
			System.out.println("Error In SQL " + ex.getMessage());
			return 0;
		}
	}
	
	private String getRoomIDBySerial(String SerialNum) {
		String sql = "SELECT RoomName FROM Rooms WHERE SerialNum='" + SerialNum + "'";
		ResultSet rs;
		try {
			rs = stmt.executeQuery(sql);
			String room = "unknown";
			while (rs.next()) {
				room = rs.getString("RoomName");
			}
			return room;
		} catch (SQLException ex) {
			System.out.println("Error In SQL " + ex.getMessage());
			return "unknown";
		}
	}
	
	private String getRoomList() {
		// handles retrieving data from the database
		PreparedStatement st;
		ResultSet rs;
		ArrayList<String> rooms = new ArrayList<String>();
		// create the select statement to retrieve the user list
		// This won't work if the rooms have been given the same name for two different rooms
		String selectSQL = "SELECT RoomName FROM Rooms";
		try {
			st = conn.prepareStatement(selectSQL);
			rs = st.executeQuery();
			// iterate the result set to get the value
			while (rs.next()) {
				rooms.add(rs.getString("RoomName"));
			}
		} catch (SQLException ex) {
			System.out.println("Error in SQL " + ex.getMessage());
		}

		String usersJson = gson.toJson(rooms);
		return usersJson;
	}

	private String getUserDetails(int userid) {
		// handles retrieving data from the database
		// create the select statement to retrieve the user list
		String selectSQL = "SELECT * FROM Users WHERE UserID = " + userid + ";";
		ResultSet rs;
		ArrayList<String> user = new ArrayList<String>();
		try {
			rs = stmt.executeQuery(selectSQL);
			if (rs.next()) {
				user.add(rs.getString("UserID"));
				user.add(rs.getString("FirstName"));
				user.add(rs.getString("LastName"));
				user.add(rs.getString("Email"));
				user.add(rs.getString("PhoneNumber"));
				user.add(rs.getString("Address"));
			}
		} catch (SQLException ex) {
			System.out.println("Error in SQL " + ex.getMessage());
		}

		String usersJson = gson.toJson(user);
		return usersJson;
	}

	private String retrieveMostRecentAttempt() {
		String sql = "SELECT UserID, RoomID, TagID, RoomName, SensorName, TimeInserted, Locked,"
				+ "SuccessFailure FROM AccessLogs ORDER BY TimeInserted DESC LIMIT 1";
		ResultSet rs;
		SensorData mostRecentAttempt = new SensorData();
		try {
			rs = stmt.executeQuery(sql);
			if (rs.next()) {
				mostRecentAttempt.setUserID(rs.getInt("UserID"));
				mostRecentAttempt.setRoomID(rs.getInt("RoomID"));
				mostRecentAttempt.setTagID(rs.getString("TagID"));
				mostRecentAttempt.setRoomName(rs.getString("RoomName"));
				mostRecentAttempt.setSensorName(rs.getString("SensorName"));
				mostRecentAttempt.setTimeInserted(rs.getString("TimeInserted"));
				mostRecentAttempt.setLocked(rs.getBoolean("Locked"));
				mostRecentAttempt.setSuccessFailure(rs.getString("SuccessFailure"));
			}
		} catch (SQLException ex) {
			System.out.println("Error In SQL " + ex.getMessage());
		}
		return gson.toJson(mostRecentAttempt);
	}

	private boolean retrieveLockStatus(int roomid) {
		String sql = "SELECT Locked FROM Rooms WHERE RoomID='" + roomid + "'";
		ResultSet rs;
		try {
			rs = stmt.executeQuery(sql);
			while (rs.next()) {
				boolean locked = rs.getBoolean("Locked");
				if (locked == false) {
					return false;
				} else {
					return true;
				}
			}
		} catch (SQLException ex) {
			System.out.println("Error In SQL " + ex.getMessage());
			return true;
		}
		// default
		return true; // door is always locked if there is an error
	}

	private String retrieveMostRecentAttemptForRoom(int roomid) {
		String sql = "SELECT UserID, RoomID, TagID, RoomName, SensorName, TimeInserted, Locked,"
				+ "SuccessFailure FROM AccessLogs WHERE RoomID = " + roomid
				+ " ORDER BY TimeInserted DESC LIMIT 1";
		ResultSet rs;
		SensorData mostRecentAttempt = new SensorData();
		try {
			rs = stmt.executeQuery(sql);
			if (rs.next()) {
				mostRecentAttempt.setUserID(rs.getInt("UserID"));
				mostRecentAttempt.setRoomID(rs.getInt("RoomID"));
				mostRecentAttempt.setTagID(rs.getString("TagID"));
				mostRecentAttempt.setRoomName(rs.getString("RoomName"));
				mostRecentAttempt.setSensorName(rs.getString("SensorName"));
				mostRecentAttempt.setTimeInserted(rs.getString("TimeInserted"));
				mostRecentAttempt.setLocked(rs.getBoolean("Locked"));
				mostRecentAttempt.setSuccessFailure(rs.getString("SuccessFailure"));
			}
		} catch (SQLException ex) {
			System.out.println("Error In SQL " + ex.getMessage());
		}
		return gson.toJson(mostRecentAttempt);
	}

	private String getUserList() {
		// handles retrieving data from the database
		PreparedStatement st;
		ResultSet rs;
		ArrayList<String> users = new ArrayList<String>();
		// create the select statement to retrieve the user list
		String selectSQL = "SELECT UserID FROM Users";
		try {
			st = conn.prepareStatement(selectSQL);
			rs = st.executeQuery();
			// iterate the result set to get the value
			while (rs.next()) {
				users.add(rs.getString("UserID"));
			}
		} catch (SQLException ex) {
			System.out.println("Error in SQL " + ex.getMessage());
		}

		String usersJson = gson.toJson(users);
		return usersJson;
	}
}
