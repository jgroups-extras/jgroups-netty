import subprocess
import time
from sys import argv

"""
Very quick and dirty script to run multiple instances of UPerf for load testing
"""

_, props, instances, delay = argv
print(props, instances)
all_pid = []
for i in range(int(instances)):
    # Host nodes must be up or everything will have merge view otherwise need to add a delay in after each process
    process = subprocess.Popen(["java", "-classpath", "./target/jgroups-netty-1.0-SNAPSHOT.jar:target/dependency/*",
                                "org.jgroups.tests.perf.UPerf", "-props", props, "-nohup"])
    all_pid.append(process)
time.sleep(int(delay))
try:
    t = input("any Button to exit")
finally:
    for i in all_pid:
        i.kill()
