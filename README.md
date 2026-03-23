# ec-backend
Environment variables
export IMAGE_STORAGE_PATH=/Users/shawndebie/Develop/storage/images

Staff Security

    # 1. Generate the private key
        openssl genrsa -out privateKey.pem 2048

    # 2. Extract the public key (which Quarkus uses to verify the token)
        openssl rsa -in privateKey.pem -pubout -out publicKey.pem

    # 3. Put both privateKey.pem and publicKey.pem in your project's src/main/resources folder.

    # 4. application.properties
        # For generating tokens (The Private Key)
        smallrye.jwt.sign.key.location=privateKey.pem

        # For verifying tokens on protected resources (The Public Key)
        mp.jwt.verify.publickey.location=publicKey.pem
        mp.jwt.verify.issuer=http://localhost:8080