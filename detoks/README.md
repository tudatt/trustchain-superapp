# Detox App
Detox is a decentralized app designed to offer a TikTok-like experience. 

## Transaction API
The app uses tokens to keep track of a user's video seeding and leeching activities.
To enable token exchange between users and maintain transaction records, we have created a transaction API.

## TODO
### TrustChain
We currently utilize TrustChain to store completed transactions between two users.
The blocks will have the #TODO type, and each block contains #TODO.

### Performance Analysis
To optimize transaction processing and identify performance bottlenecks, we added a benchmarking screen to the app and created the TransactionEngineBenchmark class.
The TransactionEngineBenchmark class contains a series of functions.
Each function in the TransactionEngineBenchmark class represents a benchmark, starting with a simple unencrypted block creation and increasing in complexity with each step until a complete transaction is created.

By building up the functionality of the app's transaction processing capabilities in this way, we are able to identify and optimize specific parts of a transaction that are causing performance issues.
Flame graphs were generated using these functions to help us to visualize and isolate performance bottlenecks for further optimization.
## TODO
#### Flame Graph Layers
The flame graphs generated for each benchmark are shown below:
<img src="https://user-images.githubusercontent.com/21971137/164292774-640abb61-cd25-4b26-8a7f-8a9f6c800332.png" width="280"><img src="https://user-images.githubusercontent.com/21971137/164292789-1d064394-87a7-4c62-a22c-602c55128be3.png" width="280">

## Usage Instructions
The app features a "Transactions" button, which takes the users to a new screen where they can run different benchmarks themselves and view a graph that shows various metrics.
## TODO add screenshots
