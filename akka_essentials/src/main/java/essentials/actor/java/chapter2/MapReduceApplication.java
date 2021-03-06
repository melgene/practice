package essentials.actor.java.chapter2;

import essentials.actor.java.chapter2.message.Result;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.pattern.Patterns;
import akka.util.Timeout;
import scala.concurrent.Await;
import scala.concurrent.Future;

import essentials.actor.java.chapter2.actor.MasterActor;

import java.util.concurrent.TimeUnit;

public class MapReduceApplication {

    public static void main(String[] args) throws Exception {
        Timeout timeout = new Timeout(1, TimeUnit.SECONDS);
        ActorSystem actorSystem = ActorSystem.create("MapReduceApp");

        ActorRef master = actorSystem.actorOf(Props.create(MasterActor.class, MasterActor::new), "master");
        master.tell("The quick brown fox tried to jump over the lazy dog and fell on the dog", master);
        master.tell("Dog is man's best friend", master);
        master.tell("Dog and Fox jump to the same family", master);
        Thread.sleep(1000);
        Future<Object> future = Patterns.ask(master, new Result(), timeout);
        String result = (String) Await.result(future, timeout.duration());
        System.out.println(result);
        actorSystem.shutdown();
    }

}