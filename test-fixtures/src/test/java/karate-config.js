function fn() {
  var env = karate.env; // get java system property 'karate.env'
  karate.log('karate.env is:', env);
  if (!env) {
    env = 'dev'; // default environment
  }
  
  var config = {
    env: env,
    baseUrl: 'https://pokeapi.co/api/v2'
  };

  // Enable pretty printing of request/response bodies
  karate.configure('logPrettyRequest', true);
  karate.configure('logPrettyResponse', true);

  return config;
}

