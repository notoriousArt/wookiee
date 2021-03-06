/*
 * Copyright 2015 Webtrends (http://www.webtrends.com)
 *
 * See the LICENCE.txt file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webtrends.harness.health

import java.util.concurrent.TimeUnit

import akka.actor.ActorDSL._
import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestProbe}
import com.typesafe.config.ConfigFactory
import com.webtrends.harness.service.messages.CheckHealth
import org.specs2.mutable.SpecificationWithJUnit

import scala.concurrent.duration._

class HealthCheckActorSpec extends SpecificationWithJUnit {

  implicit val dur = FiniteDuration(2, TimeUnit.SECONDS)

  implicit val sys = ActorSystem("system", ConfigFactory.parseString( """
    akka.actor.provider = "akka.actor.LocalActorRefProvider"
                                                                      """).withFallback(ConfigFactory.load))

  step {
    val sysActor =
      actor("system")(new Act {
        become {
          case CheckHealth => sender() ! Seq(HealthComponent("test", ComponentState.NORMAL, "test"))
        }
      })
  }

  "The health check actor" should {

    "Return system Health when asking for health information" in {

      val probe = new TestProbe(sys)
      val actor = TestActorRef(HealthCheckActor.props)

      probe.send(actor, HealthRequest(HealthResponseType.FULL))
      val msg = probe.expectMsgClass(classOf[ApplicationHealth])
      msg.applicationName equalsIgnoreCase "Webtrends Harness Service"
    }
  }

  step {
    sys.shutdown
  }
}
