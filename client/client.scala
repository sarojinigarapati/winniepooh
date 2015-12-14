import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Cancellable;
import scala.util.Random
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import spray.client.pipelining._
import spray.http._
import scala.util.{Success, Failure}
import spray.json._
import spray.httpx.SprayJsonSupport._
import spray.httpx.marshalling._
import spray.httpx.unmarshalling._
import spray.http.MediaTypes
import java.security._
import java.security.spec._
import javax.crypto._
import org.apache.commons.codec.binary.Base64

object project4 {
	def main(args: Array[String]){

		val simulationTime: Int = 20000 // in milliseconds
		val start: Long = System.currentTimeMillis
		var numOfPages: Int = 0

		case class Stat(userPosts: Int)
		case class GetProfile(userID: Int)
		case class GetAlbum(userID: Int, albumID: Int)
		case class GetPosts(userID: Int)
		case class GetFriendList(userID: Int)
		case class GetPage(userID: Int, pageID: Int)
		case class GiveAccessToFriend(frdID: Int, bytes: Array[Byte])

		case class Profile(id: String, first_name: String, last_name: String, age: String, email: String, gender: String, relation_status: String)
		case class SignUp(id: Int, pkey: Array[Byte], profile: Array[Byte])
		case class Picture(id: Int, from: Int, text: String, likes: Array[String])
		case class Album(id: Int, from: Int, pictures: Array[Picture], likes: Array[String])
		case class Page(id: Int, from: Int, name: String)
		// case class AccessFriend(userID: Int, frdID: Int, bytes: Array[Byte])
		case class AccessFriend(userID: Int, frdID: Int, bytes: String)

		object MyJsonProtocol extends DefaultJsonProtocol {
			implicit val ProfileFormat = jsonFormat7(Profile)
			implicit val SignUpFormat = jsonFormat3(SignUp)
			implicit val PictureFormat = jsonFormat4(Picture)
			implicit val AlbumFormat = jsonFormat4(Album)
			implicit val PageFormat = jsonFormat3(Page)
			implicit val AccessFriendFormat = jsonFormat3(AccessFriend)
		}

		import MyJsonProtocol._
		
		class ClientMaster(numOfUsers: Int, system: ActorSystem) extends Actor{

			var count: Int = _
			var totalPosts: Int = _

			import system.dispatcher
			val pipeline = sendReceive
			
			def receive = {
				case "Start" =>
					// tell the server about the number of users in the system and 
					// initialise the required data structures
					val responseFuture = pipeline(Post("http://localhost:8080/facebook/start?numOfUsers="+numOfUsers))
					responseFuture onComplete {
						case Success(str: HttpResponse) =>
							// println(str.entity.asString)
							self ! "StartSimulation"
						case Failure(error) =>
							println(error+"something wrong")
					}
				
				case "StartSimulation" =>
					// Create an actore for each facebook user
					println("\n**********************************************")
					println("Server is initialized! Starting the simulation!")
					println("Users doing sign up")
					println("**********************************************\n")
					for(i <- 0 until numOfUsers){
						var myID: String = i.toString
						val actor = context.actorOf(Props(new FacebookAPI(system, i, numOfUsers)),name=myID)
						actor ! "SignUp"
						actor ! "Stop"
					}

				case Stat(userPosts: Int) =>
					totalPosts = totalPosts + userPosts
					count = count + 1
					if(count == numOfUsers){
						println("Total number of posts = "+totalPosts)
						system.scheduler.scheduleOnce(5000 milliseconds, self, "ShutDown")
					}

				case "ShutDown" =>
					pipeline(Post("http://localhost:8080/facebook/shutdown"))
					context.system.shutdown()
			}
		}

		class FacebookAPI(system: ActorSystem, myID: Int, numOfUsers: Int) 
			extends Actor{

			// var kpg: KeyPairGenerator =_
			var kp: KeyPair =_
			var aesKeyProfile: SecretKey =_
			var numOfPosts: Int =_
			var numOfAlbums: Int =_
			// var cancels = new ListBuffer[Cancellable]()
			var cancelPosts1: Cancellable =_
			var cancelPosts2: Cancellable =_
			var cancelAddFriend: Cancellable =_
			var cancelAddAlbum: Cancellable =_
			var cancelAddPage: Cancellable =_
			var cancelGetProfile: Cancellable =_
			var cancelGetAlbum: Cancellable =_
			var cancelGetPage: Cancellable =_

			import system.dispatcher
			val pipeline = sendReceive

			def receive = {

				case "SignUp" =>
					// Generate RSA keys
					val kpg: KeyPairGenerator = KeyPairGenerator.getInstance("RSA")
					kpg.initialize(2048)
					kp = kpg.genKeyPair()

					 // Generate AES key
				    val kgen: KeyGenerator = KeyGenerator.getInstance("AES")
				    kgen.init(128)
				    aesKeyProfile = kgen.generateKey()

					// Create the profie data
					var id: String = myID.toString
					var first_name: String = Random.alphanumeric.take(5).mkString
					var last_name: String = Random.alphanumeric.take(5).mkString
					var age: String = (Random.nextInt(82) + 18).toString
					var email: String = Random.alphanumeric.take(10).mkString+"@facebook.com"
					var gender: String = ""
					if(Random.nextInt(1) == 0){
						gender = "Male"
					} else {
						gender = "Female"
					}
					var relation_status = ""
					if(Random.nextInt(1) == 0){
						relation_status = "Single"
					} else {
						relation_status = "Married"
					}

					// Encrypt profile
					val profile = Profile(id, first_name, last_name, age, email, gender, relation_status).toJson.toString
					val profileBytes = profile.getBytes("UTF-8")
					var aescipher: Cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
					aescipher.init(Cipher.ENCRYPT_MODE, aesKeyProfile)
					val bytes = aescipher.doFinal(profileBytes)

					val json = SignUp(myID, kp.getPublic().getEncoded(), bytes).toJson.toString
					// println("profile as json string = "+profile)
					// val profile = SignUp(myID, Profile(id, first_name, last_name, 
					// 				age, email, gender, relation_status))

					// // Encrypt Data
					// val profileBytes = profile.getBytes("UTF-8")
					// var cipher: Cipher = Cipher.getInstance("RSA")
					// cipher.init(Cipher.ENCRYPT_MODE, kp.getPublic())
					// val json = (cipher.doFinal(profileBytes)).toJson.toString

					// val responseFuture = pipeline(Post("http://localhost:8080/facebook/AddProfile?userID="+myID+"&profile="+cipherData))
					// responseFuture onComplete {
					// 	case Success(str: HttpResponse) =>
					// 		println(str.entity.asString)
					// 		str.entity.asString.parseJson.convertTo[Array[Byte]]
					// 		// self ! GetProfile(myID)
					// 		// self ! "DoActivity"
					// 	case Failure(error) =>
					// 		println(error+"something wrong")
					// }
					
					val responseFuture = pipeline(Post("http://localhost:8080/facebook/addProfile",HttpEntity(MediaTypes.`application/json`, json)))
					responseFuture onComplete {
						case Success(str: HttpResponse) =>
							// println(str.entity.asString)
							// var bytes: Array[Byte] = str.entity.asString.parseJson.convertTo[Array[Byte]]
							
							// // Decrypt Data
							// var aescipher: Cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
							// aescipher.init(Cipher.DECRYPT_MODE, aesKeyProfile)
							// val decryptedBytes = aescipher.doFinal(bytes)
							// val profile = (new String(decryptedBytes, "UTF-8")).parseJson.convertTo[Profile]
							// println(profile.toJson)

							// val json = (cipher.doFinal(bytes)).toString.parseJson.convertTo[SignUp]
							self ! GetProfile(2)
							// self ! "AddFriend"
							// self ! "DoActivity"
						case Failure(error) =>
							println(error+"something wrong")
					}

				case "DoActivity" =>
					system.scheduler.scheduleOnce(simulationTime milliseconds, self, "StopActivity")
					// cancelStop = system.scheduler.schedule(0 milliseconds,100 milliseconds,self,"Stop")
					
					cancelAddFriend = system.scheduler.schedule(0 milliseconds,2000 milliseconds,self,"AddFriend")
					// cancelAddAlbum = system.scheduler.schedule(0 milliseconds,8000 milliseconds,self,"AddAlbum")
					// cancelAddPage = system.scheduler.schedule(90 milliseconds,7000 milliseconds,self,"AddPage")
					// cancelGetAlbum = system.scheduler.schedule(30 milliseconds,3000 milliseconds,self,GetAlbum(myID, 0))
					// cancelGetProfile = system.scheduler.schedule(60 milliseconds,6000 milliseconds,self,GetProfile(Random.nextInt(numOfUsers)))
					// if(myID % 2 == 0 ){
					// 	cancelPosts1 = system.scheduler.schedule(150 milliseconds,2000 milliseconds,self,"Post")
					// } else {
					// 	cancelPosts2 = system.scheduler.schedule(180 milliseconds,4000 milliseconds,self,"Post")
					// }

				case "StopActivity" =>
					if(System.currentTimeMillis - start > simulationTime){ // in milliseconds
						// println("Shutting down the system!!")
						// cancelStop.cancel()
						cancelAddFriend.cancel()
						// cancelAddAlbum.cancel()
						// cancelAddPage.cancel()
						// cancelGetProfile.cancel()
						// cancelGetAlbum.cancel()
						// if(myID % 2 == 0 ){
						// 	cancelPosts1.cancel()
						// } else {
						// 	cancelPosts2.cancel()
						// }
						// self ! GetPosts(myID)
						// self ! GetFriendList(myID)
						context.parent ! Stat(numOfPosts)
					}

				case GetProfile(getID: Int) =>
					println("user "+myID+" requesting profile of "+getID)
					val responseFuture = pipeline(Post("http://localhost:8080/facebook/getProfile?userID="+myID+"&getID="+getID))
					responseFuture onComplete {
						case Success(str: HttpResponse) =>
							if(str.entity.asString == "PermissionDenied"){
								println("PermissionDenied for user "+myID+" to access profile of "+getID)
							} else {
								var bytes: Array[Byte] = str.entity.asString.parseJson.convertTo[Array[Byte]]
								// Decrypt Data
								var aescipher: Cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
								aescipher.init(Cipher.DECRYPT_MODE, aesKeyProfile)
								val decryptedBytes = aescipher.doFinal(bytes)
								val profile = (new String(decryptedBytes, "UTF-8")).parseJson.convertTo[Profile]
								println(profile.toJson)
							}
						case Failure(error) =>
							println(error+"something wrong")
					}

				case GetPage(userID: Int, pageID: Int) =>
					val responseFuture = pipeline(Post("http://localhost:8080/facebook/getPage?userID="+userID+"&pageID="+pageID))
					responseFuture onComplete {
						case Success(str: HttpResponse) =>
							val page = str.entity.asString.parseJson.convertTo[Page]
							println("user "+myID+" received page of "+userID+"\n"+
								"\npageID "+page.id+
								"\nname "+page.name)
						case Failure(error) =>
							println(error+"something wrong")
					}

				case GetPosts(userID: Int) =>
					val responseFuture = pipeline(Post("http://localhost:8080/facebook/getPage?userID="+userID))
					responseFuture onComplete {
						case Success(str: HttpResponse) =>
							println("user "+myID+" received posts of "+userID+"\n"+
								str.entity.asString)
						case Failure(error) =>
							println(error+"something wrong")
					}

				case GetFriendList(userID: Int) =>
					// println("Asking for friend list")
					val responseFuture = pipeline(Post("http://localhost:8080/facebook/getFriendList?userID="+userID))
					responseFuture onComplete {
						case Success(str: HttpResponse) =>
							var frdListArray: Array[Int] = str.entity.asString.parseJson.convertTo[Array[Int]]
							println(frdListArray.toJson)
							// println("user "+myID+" received friend list of "+userID+"\n"+
							// 	str.entity.asString)
						case Failure(error) =>
							println(error+"something wrong")
					}

				case GetAlbum(userID: Int, albumID: Int) =>
					val responseFuture = pipeline(Post("http://localhost:8080/facebook/getAlbum?userID="+userID+"&albumID="+albumID))
					responseFuture onComplete {
						case Success(str: HttpResponse) =>
							val album = str.entity.asString.parseJson.convertTo[Album]
							println("user "+myID+" received album of "+userID+
								"\nalbumID "+album.id+
								"\nlikes "+album.likes.mkString(","))
						case Failure(error) =>
							println(error+"something wrong")
					}

				case "Post" =>
					var text: String = Random.alphanumeric.take(10).mkString
					// var text: String = "Post by user "+myID
					val responseFuture = pipeline(Post("http://localhost:8080/facebook/post?userID="+myID+"&text="+text))
					responseFuture onComplete {
						case Success(str: HttpResponse) =>
							// println("User "+myID+" successfully posted "+numOfPosts+1)
							numOfPosts = numOfPosts + 1
							// println(str.entity.asString)
						case Failure(error) =>
							println(error+"something wrong")
					}

				case "AddFriend" =>
					var frdID: Int = Random.nextInt(numOfUsers)
					while(frdID == myID){
						frdID = Random.nextInt(numOfUsers)
					}
					val responseFuture = pipeline(Post("http://localhost:8080/facebook/addFriend?userID="+myID+"&frdID="+frdID))
					responseFuture onComplete {
						case Success(str: HttpResponse) =>
							// println(str.entity.asString)
							if(str.entity.asString == "AlreadyFriend"){
								println("user "+frdID+" is already a friend to user "+myID)
							} else {
								var bytes: Array[Byte] = str.entity.asString.parseJson.convertTo[Array[Byte]]
								// self ! GiveAccessToFriend(frdID, bytes)
							}
						case Failure(error) =>
							println(error+"something wrong")
					}

				case GiveAccessToFriend(frdID: Int, bytes: Array[Byte]) =>
					// Generate the friends public key
					val kf: KeyFactory = KeyFactory.getInstance("RSA"); // or "EC" or whatever
					val frdPublicKey: PublicKey = kf.generatePublic(new X509EncodedKeySpec(bytes))

					// Encrypt your profile AES secret key with frd's public key
					var rsaCipher: Cipher = Cipher.getInstance("RSA")
					rsaCipher.init(Cipher.ENCRYPT_MODE, frdPublicKey)
					val encryptedString = Base64.encodeBase64String(rsaCipher.doFinal(aesKeyProfile.getEncoded()))

					// Send the encrypted AES secret key for the server to store
					val json = AccessFriend(myID, frdID, encryptedString).toJson.toString
					val responseFuture = pipeline(Post("http://localhost:8080/facebook/accessFriend",HttpEntity(MediaTypes.`application/json`, json)))
					responseFuture onComplete {
						case Success(str: HttpResponse) =>
							println(str.entity.asString)
						case Failure(error) =>
							println(error+"something wrong")
					}

				case "AddPage" =>
					var id: Int = numOfPages
					numOfPages = numOfPages + 1
					var name: String = "page-"+Random.nextInt(numOfUsers).toString
					val json = Page(id,myID,name).toJson.toString
					val responseFuture = pipeline(Post("http://localhost:8080/facebook/addPage?pageID=",HttpEntity(MediaTypes.`application/json`, json)))
					responseFuture onComplete {
						case Success(str: HttpResponse) =>
							// println(str.entity.asString)
						case Failure(error) =>
							println(error+"something wrong")
					}

				case "AddAlbum" =>
					var likes: ArrayBuffer[String] = ArrayBuffer[String](Random.nextInt(numOfUsers).toString)
					var avgNumOfLikes = Random.nextInt(20)
					for(i <- 0 to avgNumOfLikes){
						likes += Random.nextInt(numOfUsers).toString
					}
					var picturelikes: ArrayBuffer[String] = ArrayBuffer[String](Random.nextInt(numOfUsers).toString)
					for(i <- 0 to avgNumOfLikes){
						likes += Random.nextInt(numOfUsers).toString
					}
					var id: Int = Random.nextInt(100)
					var pictures: ArrayBuffer[Picture] = ArrayBuffer[Picture](Picture(id,myID,("pic-"+Random.nextInt(numOfUsers).toString),likes.toArray))
					
					var avgNumOfPictures = Random.nextInt(10)
					for(i <- 0 to avgNumOfPictures){
						var picturelikes: ArrayBuffer[String] = ArrayBuffer[String](Random.nextInt(numOfUsers).toString)
						for(i <- 0 to avgNumOfLikes){
							likes += Random.nextInt(numOfUsers).toString
						}
						var id: Int = Random.nextInt(100)
						pictures += Picture(id,myID,("pic-"+Random.nextInt(numOfUsers).toString),likes.toArray)
						
					}
					val json = Album(numOfAlbums,myID, pictures.toArray, likes.toArray).toJson.toString
					// val json = new Album(numOfAlbums.toString,myID.toString,likes).toJson.toString
					// val json = AlbumWrapper(myID, numOfAlbums, new Album(numOfAlbums.toString,myID.toString,likes)).toJson.toString
					numOfAlbums = numOfAlbums + 1
					val responseFuture = pipeline(Post("http://localhost:8080/facebook/addAlbum",HttpEntity(MediaTypes.`application/json`, json)))
					responseFuture onComplete {
						case Success(str: HttpResponse) =>
							// println(str.entity.asString)
						case Failure(error) =>
							println(error+"something wrong")
					}
			}
		}

		if(1>args.size){
			println("Please enter number of facebook users to start simulation!")
		} else {
			implicit val system = ActorSystem("ClientSystem")
			val clientMaster =system.actorOf(Props(new ClientMaster((args(0).toInt),system)),name="clientMaster")
			clientMaster ! "Start"
		}
		
	}	
}
