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

package com.webtrends.harness.service.test

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.pattern._
import akka.util.Timeout
import ch.qos.logback.classic.Level
import com.typesafe.config.{Config, ConfigFactory}
import com.webtrends.harness.app.HarnessActor.{SystemReady, GetManagers, ReadyCheck}
import com.webtrends.harness.app.Harness
import com.webtrends.harness.component.{LoadComponent, Component}
import com.webtrends.harness.service.Service
import com.webtrends.harness.service.messages.{Ready, LoadService}
import com.webtrends.harness.HarnessConstants._

import scala.concurrent.Await
import scala.concurrent.duration._

object TestHarness {
  var harness:Option[TestHarness] = None

  /**
   * Create a new instance of the test harness and start all of it's components
   * @param config the config to use
   * @return
   */
  def apply(config:Config,
            serviceMap:Option[Map[String, Class[_ <: Service]]]=None,
            componentMap:Option[Map[String, Class[_ <: Component]]]=None, logLevel:Level=Level.ERROR) : TestHarness = {
    harness match {
      case Some(h) => h
      case None =>
        harness = Some(new TestHarness(config).start(serviceMap, componentMap, logLevel))
        harness.get
    }
  }

  def system = Harness.getActorSystem
  def log = Harness.getLogger
  def rootActor = Harness.getRootActor

  def shutdown = harness match {
    case Some(h) => h.stop
    case None => // ignore
  }
}

class TestHarness(conf:Config) {
  var services = Map[String, ActorRef]()
  var components = Map[String, ActorRef]()
  var serviceManager: Option[ActorRef] = None
  var componentManager: Option[ActorRef] = None
  var commandManager: Option[ActorRef] = None
  var config = conf.withFallback(defaultConfig)
  config = config.withFallback(config.getConfig("wookiee-system")).resolve()

  implicit val timeout = Timeout(4000, TimeUnit.MILLISECONDS)

  def start(serviceMap:Option[Map[String, Class[_ <: Service]]]=None,
            componentMap:Option[Map[String, Class[_ <: Component]]]=None, logLevel:Level=Level.ERROR) : TestHarness = {
    Harness.externalLogger.info("Starting Harness...")
    Harness.externalLogger.info(s"Test Harness Config: ${config.toString}")
    Harness.addShutdownHook
    Harness.startActorSystem(Some(config))
    // after we have started the TestHarness we need to set the serviceManager, ComponentManager and CommandManager from the Harness
    harnessReadyCheck(10.seconds.fromNow)
    Await.result(TestHarness.rootActor.get ? GetManagers, 5.seconds) match {
      case m =>
        val map = m.asInstanceOf[Map[String, ActorRef]]
        serviceManager = map.get(ServicesName)
        commandManager = map.get(CommandName)
        componentManager = map.get(ComponentName)
        TestHarness.log.get.info("Managers all accounted for")
    }
    setLogLevel(logLevel)
    if (componentMap.isDefined) {
      loadComponents(componentMap.get)
    }
    if (serviceMap.isDefined) {
      loadServices(serviceMap.get)
    }
    componentManager.foreach {_ ! SystemReady}
    serviceManager.foreach {_ ! SystemReady}
    this
  }

  def stop = {
    Harness.shutdownActorSystem(false) {
      // wait a second to make sure it shutdown correctly
      Thread.sleep(1000)
    }
  }

  def setLogLevel(level:Level) = {
    TestHarness.log.get.setLogLevel(level)
  }

  def harnessReadyCheck(timeOut: Deadline) {
    while(!timeOut.isOverdue() && !Await.result(TestHarness.rootActor.get ? ReadyCheck, 10.seconds).asInstanceOf[Boolean]) {
    }

    if (timeOut.isOverdue()) {
      throw new IllegalStateException("HarnessActor did not start up")
    }
  }

  def getService(service: String): Option[ActorRef] = {
    services.get(service)
  }

  def getComponent(component: String): Option[ActorRef] = {
    components.get(component)
  }

  def loadComponents(componentMap: Map[String, Class[_ <: Component]]) = {
    componentMap foreach { p =>
      componentReady(5.seconds.fromNow, p._1, p._2.getCanonicalName)
    }
  }
  
  def loadServices(serviceMap: Map[String, Class[_ <: Service]]) = {
    serviceMap foreach { p =>
      serviceReady(5.seconds.fromNow, p._1, p._2)
    }
  }

  private def componentReady(timeOut: Deadline, componentName: String, componentClass: String) {
    if (timeOut.isOverdue()) {
      throw new IllegalStateException(s"Component $componentName did not start up")
    }
    Await.result(componentManager.get ? LoadComponent(componentName, componentClass), 5.seconds) match {
      case Some(m) =>
        val component = m.asInstanceOf[ActorRef]
        TestHarness.log.get.info(s"Loaded component $componentName, ${component.path.toString}")
        components += (componentName -> component)
      case None =>
        throw new Exception("Component not returned")
    }
  }

  private def serviceReady(timeOut: Deadline, serviceName: String, serviceClass: Class[_ <: Service]) {
    if (timeOut.isOverdue()) {
      throw new IllegalStateException(s"Service $serviceName did not start up")
    }
    Await.result(serviceManager.get ? LoadService(serviceName, serviceClass), 3.seconds) match {
      case Some(m) =>
        val service = m.asInstanceOf[ActorRef]
        TestHarness.log.get.info(s"Loaded service $serviceName, ${service.path.toString}")
        services += (serviceName -> service)
      case None =>
        throw new Exception("Service not returned")
    }
  }

  def defaultConfig : Config = {
    ConfigFactory.parseString(
      """
        wookiee-system {
          prepare-to-shutdown-timeout = 1
        }
        services {
          path = ""
          distinct-classloader = false
        }
        components {
          path = ""
        }
        test-mode = true
        internal-http {
          enabled = false
        }
        # CIDR Rules
        cidr-rules {
          # This is a list of IP ranges to allow through. Can be empty.
          allow = ["127.0.0.1/30", "10.0.0.0/8"]
          # This is a list of IP ranges to specifically deny access. Can be empty.
          deny = []
        }
        message-processor {
          # How often the MessageProcessor should share it's subscription information
          share-interval = 1s
          # When should MessageTopicProcessor instances be removed after there are no longer any subscribers for that topic
          trash-interval = 30s
          # The default send timeout
          default-send-timeout = 2s
        }
        commands {
          # generally this should be enabled
          enabled = true
          default-nr-routees = 5
        }
      """)
  }
}
